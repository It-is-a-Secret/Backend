package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.repository.RoomUnreadCount;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메시지 저장/조회/읽음 처리 담당. 실시간 송신·읽음 갱신과 이력 조회·안읽음 집계를 제공한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;

  private final ChatMessageRepository chatMessageRepository;
  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMembershipReader membershipReader;

  /**
   * 실시간 메시지를 저장하고 미리보기를 갱신한다(설계 §7-3). 발신자는 STOMP principal에서 넘어온 {@code senderId}이며,
   * 클라이언트가 보낸 값은 신뢰하지 않는다(§9 발신자 위조 방지). 참여자·방 ACTIVE 검증은 {@link ChatRoomMembershipReader}로
   * 선행하므로, 비참여자({@code NOT_PARTICIPANT})·종료된 방({@code ROOM_CLOSED})으로의 송신은 여기서 차단된다.
   * 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   *
   * <p>미리보기({@code lastMessageId})는 동시 송신 경합에서 과거 id로 되돌아가지 않도록 조건부 UPDATE로 전진만 시킨다.
   * 저장 후 {@code /topic/rooms/{roomId}} 브로드캐스트는 호출 측(STOMP 컨트롤러)이 반환된 응답으로 수행한다.
   */
  @Transactional
  public ChatMessageResponse send(Long roomId, Long senderId, ChatMessageSendRequest request) {
    ChatRoomMember membership = membershipReader.getWritableMembership(roomId, senderId);
    ChatMessage message = createMessage(membership.getChatRoom(), membership.getMember(), request);
    chatMessageRepository.save(message);
    chatRoomRepository.advanceLastMessage(roomId, message.getId());
    return ChatMessageResponse.from(message);
  }

  /**
   * 요청 타입에 따라 메시지를 만든다. 사람이 보내는 {@code TEXT}/{@code IMAGE}만 허용하고, 서버 전용인 {@code SYSTEM}이나
   * 빈 본문은 {@code INVALID_MESSAGE}(400)로 막는다(도메인 팩토리의 {@link IllegalArgumentException}을 도메인 예외로 변환).
   */
  private ChatMessage createMessage(ChatRoom room, Member sender, ChatMessageSendRequest request) {
    if (request.type() != ChatMessageType.TEXT && request.type() != ChatMessageType.IMAGE) {
      throw BaseException.from(ChatErrorCode.INVALID_MESSAGE);
    }
    try {
      return request.type() == ChatMessageType.IMAGE
          ? ChatMessage.createImageMessage(room, sender, request.content())
          : ChatMessage.createTextMessage(room, sender, request.content());
    } catch (IllegalArgumentException e) {
      throw BaseException.from(ChatErrorCode.INVALID_MESSAGE);
    }
  }

  /**
   * 읽음 위치를 전진시킨다(설계 §7-4, WebSocket 우선). 참여자 검증 후 {@link ChatRoomMember#readUpTo}로 갱신하므로
   * 더 과거의 id로는 되돌아가지 않는다. 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   *
   * <p>커서({@code lastReadMessageId})는 클라이언트가 보낸 값이므로 그 방에 실제로 존재하는 메시지인지 먼저 검증한다.
   * 방에 없는 id(예: {@code Long.MAX_VALUE})를 허용하면 이후 메시지까지 읽음 처리돼 안읽음 카운트가 영구 무력화되기 때문이다.
   */
  @Transactional
  public void markAsRead(Long roomId, Long memberId, Long lastReadMessageId) {
    ChatRoomMember membership = membershipReader.getWritableMembership(roomId, memberId);
    if (!chatMessageRepository.existsByIdAndChatRoom_Id(lastReadMessageId, roomId)) {
      throw BaseException.from(ChatErrorCode.INVALID_MESSAGE);
    }
    membership.readUpTo(lastReadMessageId);
  }

  /**
   * 방의 메시지 이력을 id 커서 페이지네이션으로 조회한다(최신순). 참여자 검증은 호출 측(Facade)에서 선행한다.
   * {@code size}는 {@code [1, 100]}으로 보정해 잘못된 값(0/음수 → 예외, 과도하게 큰 값 → 과부하)을 방지한다.
   */
  public List<ChatMessageResponse> getHistory(Long roomId, Long cursor, int size) {
    int pageSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    return chatMessageRepository.findHistory(roomId, cursor, PageRequest.ofSize(pageSize))
        .stream()
        .map(ChatMessageResponse::from)
        .toList();
  }

  /** 단건 방의 안읽음 수를 계산한다(목록은 {@link #getUnreadCounts}로 배치 집계). */
  public long getUnreadCount(Long roomId, Long lastReadMessageId, Long memberId) {
    return chatMessageRepository.countUnreadInRoom(roomId, lastReadMessageId, memberId);
  }

  /** 내가 참여 중인 방들의 안읽음 수를 {@code roomId → count} 맵으로 한 번에 계산한다(N+1 회피). */
  public Map<Long, Long> getUnreadCounts(Long memberId) {
    return chatMessageRepository.countUnreadByRoom(memberId)
        .stream()
        .collect(Collectors.toMap(RoomUnreadCount::getRoomId, RoomUnreadCount::getUnreadCount));
  }
}
