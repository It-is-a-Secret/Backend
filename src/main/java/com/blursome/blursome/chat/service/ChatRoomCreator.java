package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.service.MemberService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방+참여자 생성과 경합 복구 조회를 각각 독립 트랜잭션(REQUIRES_NEW)으로 수행한다.
 * 생성 트랜잭션이 유니크 제약 위반으로 롤백돼도 호출 측 트랜잭션은 깨지지 않으며,
 * 복구 조회는 새 트랜잭션(=새 스냅샷)에서 돌아 상대가 방금 커밋한 ACTIVE 방을 볼 수 있다(완전 멱등, 설계 §7-1).
 */
@Component
@RequiredArgsConstructor
class ChatRoomCreator {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final MemberService memberService;

  /**
   * 새 방과 두 참여자를 저장한다. {@code saveAndFlush}로 {@code active_pair_key} 유니크 제약을 즉시 검증하므로
   * 동시 개설 경합에서 지면 이 트랜잭션에서 제약 위반이 발생하고, 이 트랜잭션만 롤백된다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ChatRoom create(Long memberAId, Long memberBId) {
    Member memberA = memberService.findActiveMember(memberAId);
    Member memberB = memberService.findActiveMember(memberBId);
    ChatRoom room = chatRoomRepository.saveAndFlush(
        ChatRoom.createOnMatched(memberAId, memberBId));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, memberA));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, memberB));
    return room;
  }

  /**
   * 경합 복구용 재조회. 반드시 새 트랜잭션에서 실행해 상대가 방금 커밋한 ACTIVE 방을 본다
   * (호출 측 트랜잭션은 이미 스냅샷이 고정돼 새 방을 못 볼 수 있으므로).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ChatRoom> findActiveRoomInNewTx(Long memberAId, Long memberBId) {
    return chatRoomMemberRepository.findActiveRoomBetween(memberAId, memberBId);
  }
}
