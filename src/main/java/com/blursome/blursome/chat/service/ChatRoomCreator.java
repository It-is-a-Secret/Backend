package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;
import com.blursome.blursome.chat.event.ChatRoomNotificationEvent;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.service.MemberService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방+참여자+첫 메시지 생성과 경합 복구 조회를 각각 독립 트랜잭션(REQUIRES_NEW)으로 수행한다.
 * 생성 트랜잭션이 유니크 제약 위반으로 롤백돼도 호출 측 트랜잭션은 깨지지 않으며,
 * 복구 조회는 새 트랜잭션(=새 스냅샷)에서 돌아 상대가 방금 커밋한 ACTIVE 방을 볼 수 있다(완전 멱등, 설계 §7-1).
 */
@Component
@RequiredArgsConstructor
class ChatRoomCreator {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final MemberService memberService;
  private final ChatMessageService chatMessageService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 새 방·두 참여자·개설자({@code initiatorId})의 첫 메시지를 <b>하나의 트랜잭션</b>으로 저장한다(이슈 #87).
   * {@code saveAndFlush}로 {@code pair_key} 유니크 제약을 즉시 검증하므로 동시 첫 접촉 경합에서 지면 이 트랜잭션에서
   * 제약 위반이 발생하고 이 트랜잭션만 롤백된다(방·메시지 모두 롤백 → 빈 방이 남지 않음). 첫 메시지 전송이 실패해도
   * 방까지 함께 롤백돼 "첫 접촉인데 빈 방만 생성"되는 비원자 상태가 생기지 않는다 — 호출 측은 REQUIRES_NEW로 격리된
   * 이 단위가 통째로 커밋된 뒤에야 결과를 본다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RoomOpenResult createWithFirstMessage(Long initiatorId, Long partnerId,
      ChatMessageSendRequest firstMessage) {
    Member initiator = memberService.findActiveMember(initiatorId);
    Member partner = memberService.findActiveMember(partnerId);
    ChatRoom room = chatRoomRepository.saveAndFlush(
        ChatRoom.createOnMatched(initiatorId, partnerId));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, initiator));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, partner));
    // 같은 (REQUIRES_NEW) 트랜잭션에서 첫 메시지를 보낸다 — send는 REQUIRED라 이 트랜잭션에 합류하므로 방·메시지가
    // 원자적으로 커밋된다. 방금 flush된 방 행을 send가 비관적 락으로 잡아 진행도 카운트까지 일관 처리한다.
    ChatMessageResponse sent = chatMessageService.send(room.getId(), initiatorId, firstMessage);
    // 수신자(상대)는 이 방을 구독한 적이 없으므로 방 토픽으로는 첫 메시지가 닿지 않는다. 개인 큐로 NEW_ROOM을
    // 발행해(커밋 이후 전송) 상대가 방 목록에 새 대화 항목을 즉시 띄우게 한다(이슈 #88). 새 방 항목 렌더용으로
    // 개설자 닉네임을 함께 싣는다. send()가 발행하는 NEW_MESSAGE도 상대에게 가지만, 둘 다 messageId 기준 멱등
    // 신호라 클라이언트가 덮어쓰기로 처리한다.
    eventPublisher.publishEvent(new ChatRoomNotificationEvent(
        partnerId,
        ChatRoomNotificationResponse.newRoom(
            room.getId(), initiatorId, initiator.getNickName(), sent)));
    return new RoomOpenResult(room, true, sent);
  }

  /**
   * 경합 복구용 재조회(이슈 #87). 반드시 새 트랜잭션에서 실행해 상대가 방금 커밋한 방을 본다(호출 측 트랜잭션은 이미
   * 스냅샷이 고정돼 새 방을 못 볼 수 있으므로). 페어당 방이 영구히 하나이므로 페어 키로 상태 무관하게 단건 조회한다 —
   * 호출 측이 상태별로 평가한다(보통 상대가 만든 ACTIVE 방).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ChatRoom> findRoomInNewTx(Long memberAId, Long memberBId) {
    return chatRoomRepository.findByPairKey(ChatRoom.pairKey(memberAId, memberBId));
  }
}
