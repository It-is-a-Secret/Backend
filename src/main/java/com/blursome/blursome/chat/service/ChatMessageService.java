package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRevealPolicy;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;
import com.blursome.blursome.chat.event.ChatMessageBroadcastEvent;
import com.blursome.blursome.chat.event.ChatProgressAdvancedEvent;
import com.blursome.blursome.chat.event.ChatRoomNotificationEvent;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.repository.RoomUnreadCount;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final ChatRoomMembershipReader membershipReader;
  private final ChatRevealPolicy revealPolicy;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * 실시간 메시지를 저장하고 미리보기를 갱신한다(설계 §7-3). 발신자는 STOMP principal에서 넘어온 {@code senderId}이며,
   * 클라이언트가 보낸 값은 신뢰하지 않는다(§9 발신자 위조 방지). 참여자·방 ACTIVE 검증은 {@link ChatRoomMembershipReader}로
   * 선행하므로, 비참여자({@code NOT_PARTICIPANT})·종료된 방({@code ROOM_CLOSED})으로의 송신은 여기서 차단된다.
   * 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   *
   * <p>미리보기({@code lastMessageId})는 동시 송신 경합에서 과거 id로 되돌아가지 않도록 조건부 UPDATE로 전진만 시킨다.
   * {@code /topic/rooms/{roomId}} 브로드캐스트는 커밋 이후 {@link ChatMessageBroadcastEvent} 리스너가 수행한다 —
   * 같은 트랜잭션에서 트리거 메시지를 먼저 발행하고, 단계 상승 시 SYSTEM 안내 메시지를 뒤에 발행하므로 전송
   * 순서가 트리거 → 안내로 보장된다(이슈 #85). 반환값은 STOMP 응답·테스트용이다.
   */
  @Transactional
  public ChatMessageResponse send(Long roomId, Long senderId, ChatMessageSendRequest request) {
    ChatRoomMember membership = membershipReader.getWritableMembership(roomId, senderId);
    ChatMessage message = createMessage(membership.getChatRoom(), membership.getMember(), request);
    chatMessageRepository.save(message);
    chatRoomRepository.advanceLastMessage(roomId, message.getId());
    ChatMessageResponse response = ChatMessageResponse.from(message);
    eventPublisher.publishEvent(new ChatMessageBroadcastEvent(roomId, response));
    publishNewMessageNotifications(roomId, senderId, response);
    applyRevealProgress(membership, message);
    return response;
  }

  /**
   * 방 토픽 브로드캐스트와 별개로, 발신자를 뺀 나머지 참여자의 개인 큐로 {@code NEW_MESSAGE}를 팬아웃한다
   * (이슈 #88). 수신자가 그 방을 구독 중이 아니어도(방 목록 등 다른 화면) 방 목록·안읽음 배지를 즉시 갱신할 수
   * 있게 하는 신호다. 발신자 본인은 제외해 자기 메시지로 자기 개인 큐가 울리지 않는다. 1:1 방이라 보통 상대 1명에게
   * 발행되며, 전송은 방 토픽과 동일하게 커밋 이후({@link com.blursome.blursome.chat.event.ChatRoomNotificationListener})
   * 수행된다.
   *
   * <p>단계 상승 {@code SYSTEM} 안내 메시지는 트리거 메시지 직후에 같은 방에 기록되며, 수신자는 이 트리거
   * 메시지의 {@code NEW_MESSAGE}로 이미 목록이 갱신되므로 별도 팬아웃하지 않는다(중복 알림 방지).
   */
  private void publishNewMessageNotifications(
      Long roomId, Long senderId, ChatMessageResponse message) {
    for (Long recipientId : chatRoomMemberRepository.findOtherMemberIds(roomId, senderId)) {
      eventPublisher.publishEvent(new ChatRoomNotificationEvent(
          recipientId, ChatRoomNotificationResponse.newMessage(roomId, senderId, message)));
    }
  }

  /**
   * 유효 메시지면 발신자 카운트를 올리고, 양방향 누적 기준({@code min(A, B)})을 넘기면 방의 사진 공개 단계를
   * 전진시킨다(이슈 #79). 단계가 실제로 바뀐 경우에만 공개 안내 SYSTEM 메시지를 타임라인에 기록하고
   * {@link ChatProgressAdvancedEvent}를 발행해 WebSocket 브로드캐스트를 트리거한다.
   *
   * <p>유효 판정: {@code TEXT}이고({@code IMAGE}/{@code SYSTEM} 제외) 본문 {@code trim} 후 최소 길이 이상이며,
   * 직전에 유효 카운트된 발신자와 다른 발신자(교대 발화)이고, 발신자별 디바운스 간격을 지났을 때다. 이 메서드는
   * {@code send}의 쓰기 트랜잭션 안에서 호출되며, {@code getWritableMembership}이 방 행을 비관적 락으로 잡아
   * 방 단위로 송신을 직렬화하므로 카운트 증분·단계 전이를 read-modify-write로 안전하게 수행한다(설계 §7-6 락 순서).
   */
  private void applyRevealProgress(ChatRoomMember sender, ChatMessage message) {
    if (message.getType() != ChatMessageType.TEXT
        || !revealPolicy.isCountableContent(message.getContent())) {
      return;
    }
    ChatRoom room = sender.getChatRoom();
    Long senderMemberId = sender.getMember().getId();
    LocalDateTime now = LocalDateTime.now(clock);
    if (!room.isAlternatingSender(senderMemberId)
        || !sender.isCountEligible(now, revealPolicy.countDebounce())) {
      return;
    }
    sender.countValidMessage(now);
    room.recordValidSender(senderMemberId);

    ChatRoomMember partner = findPartner(room.getId(), sender);
    long min = Math.min(sender.getValidMessageCount(), partner.getValidMessageCount());
    if (room.advanceProgressTo(revealPolicy.statusFor(min))) {
      recordRevealSystemMessage(room);
      eventPublisher.publishEvent(
          new ChatProgressAdvancedEvent(room.getId(), room.getProgressStatus()));
    }
  }

  /**
   * 단계가 실제로 상승했을 때 공개 안내 SYSTEM 메시지를 저장하고, 미리보기 갱신과 브로드캐스트 발행까지 처리한다
   * (이슈 #85). 문구는 도달한 단계({@link ChatRoomProgressStatus#revealSystemMessage()})가 단일 출처로 정한다.
   * 단계 상승은 항상 {@code PHOTO_REVEAL_STEP_1} 이상으로만 일어나 문구가 항상 존재하므로 {@code createSystemMessage}가
   * 빈 본문으로 막히지 않는다. 미리보기({@code lastMessageId})는 트리거 메시지보다 뒤에 저장돼 더 큰 id이므로 조건부
   * UPDATE로 그대로 전진한다. 브로드캐스트 이벤트는 트리거 메시지({@link ChatMessageBroadcastEvent}) <b>다음</b>에
   * 발행돼, 커밋 이후 전송 순서가 트리거 → 안내가 된다.
   */
  private void recordRevealSystemMessage(ChatRoom room) {
    ChatMessage systemMessage =
        ChatMessage.createSystemMessage(room, room.getProgressStatus().revealSystemMessage());
    chatMessageRepository.save(systemMessage);
    chatRoomRepository.advanceLastMessage(room.getId(), systemMessage.getId());
    eventPublisher.publishEvent(
        new ChatMessageBroadcastEvent(room.getId(), ChatMessageResponse.from(systemMessage)));
  }

  /** 1:1 방에서 발신자를 제외한 상대 참여 행을 찾는다(양방향 min 카운트 계산용). */
  private ChatRoomMember findPartner(Long roomId, ChatRoomMember sender) {
    return chatRoomMemberRepository.findAllByRoomId(roomId).stream()
        .filter(member -> !member.getId().equals(sender.getId()))
        .findFirst()
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
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
   * 읽음 위치를 전진시킨다(설계 §7-4, WebSocket 우선). 참여자 검증 후 조건부 UPDATE
   * ({@link ChatRoomMemberRepository#advanceLastReadMessage})로 더 큰 커서일 때만 원자적으로 전진시키므로
   * 동시 읽음 요청에서도 더 과거의 id로 되돌아가지 않는다. 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   *
   * <p>커서({@code lastReadMessageId})는 클라이언트가 보낸 값이므로 그 방에 실제로 존재하는 메시지인지 먼저 검증한다.
   * 방에 없는 id(예: {@code Long.MAX_VALUE})를 허용하면 이후 메시지까지 읽음 처리돼 안읽음 카운트가 영구 무력화되기 때문이다.
   *
   * @return 갱신 후 내 실제 읽음 커서(이미 더 큰 커서가 있었으면 그 값). 호출 측(STOMP 컨트롤러)이 이 값으로 읽음 표시를 브로드캐스트한다.
   */
  @Transactional
  public Long markAsRead(Long roomId, Long memberId, Long lastReadMessageId) {
    membershipReader.getWritableMembership(roomId, memberId);
    if (!chatMessageRepository.existsByIdAndChatRoom_Id(lastReadMessageId, roomId)) {
      throw BaseException.from(ChatErrorCode.INVALID_MESSAGE);
    }
    // 동시 읽음 경합에서 커서가 역행하지 않도록 조건부 UPDATE로 원자적 단조 증가시킨다(엔티티 read-modify-write 회피).
    chatRoomMemberRepository.advanceLastReadMessage(roomId, memberId, lastReadMessageId);
    // 브로드캐스트에는 실제 전진된 커서를 실어야 한다 — 이미 더 큰 커서가 있었으면(조건부 UPDATE no-op) 그 값을 읽어 반환한다.
    return chatRoomMemberRepository.findLastReadMessageId(roomId, memberId).orElse(lastReadMessageId);
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

  /**
   * 여러 방의 마지막 메시지 미리보기를 {@code messageId → 응답} 맵으로 한 번에 조회한다(방 목록 N+1 회피).
   * null·중복 id는 걸러 한 번의 쿼리로만 조회하며, 조회할 id가 없으면 빈 맵을 반환한다.
   */
  public Map<Long, ChatMessageResponse> getLastMessages(Collection<Long> messageIds) {
    List<Long> ids = messageIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return chatMessageRepository.findAllByIdInWithSender(ids)
        .stream()
        .collect(Collectors.toMap(ChatMessage::getId, ChatMessageResponse::from));
  }

  /** 단건 방의 마지막 메시지 미리보기를 조회한다. 메시지가 없으면({@code messageId == null}) null을 반환한다. */
  public ChatMessageResponse getLastMessage(Long messageId) {
    if (messageId == null) {
      return null;
    }
    return chatMessageRepository.findAllByIdInWithSender(List.of(messageId))
        .stream()
        .findFirst()
        .map(ChatMessageResponse::from)
        .orElse(null);
  }
}
