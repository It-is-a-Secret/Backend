package com.blursome.blursome.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRevealPolicy;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.event.ChatProgressAdvancedEvent;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

  private static final Long ROOM_ID = 10L;
  private static final Long SENDER_ID = 100L;
  private static final Long PARTNER_ID = 200L;
  private static final Long MESSAGE_ID = 5L;
  private static final Instant FIXED = Instant.parse("2026-06-25T00:00:00Z");

  @Mock
  private ChatMessageRepository chatMessageRepository;

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Mock
  private ChatRoomMembershipReader membershipReader;

  // 정책은 실제 동작을 그대로 쓴다(임계값·길이·디바운스 검증은 ChatRevealPolicyTest가 담당).
  @Spy
  private ChatRevealPolicy revealPolicy = new ChatRevealPolicy();

  @Mock
  private ApplicationEventPublisher eventPublisher;

  // 고정 Clock으로 "현재 시각"을 통제한다(발신자별 디바운스 판정).
  @Spy
  private Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

  @InjectMocks
  private ChatMessageService chatMessageService;

  @Test
  @DisplayName("TEXT 메시지를 저장하고 방 미리보기를 원자적으로 전진시킨 응답을 반환한다")
  void send_whenText_thenSavesAndAdvancesPreview() {
    // given
    ChatRoom room = activeRoom();
    ChatRoomMember membership = membership(SENDER_ID, 1L, room);
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(membership);
    givenSavedMessage();
    // 유효 메시지라 공개 단계 카운트 경로를 타므로 상대 멤버십도 조회된다(min(1,0)=0이라 단계는 그대로).
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
        .willReturn(List.of(membership, membership(PARTNER_ID, 2L, room)));
    ChatMessageSendRequest request = new ChatMessageSendRequest(ChatMessageType.TEXT, "안녕하세요");

    // when
    ChatMessageResponse response = chatMessageService.send(ROOM_ID, SENDER_ID, request);

    // then
    assertThat(response.messageId()).isEqualTo(MESSAGE_ID);
    assertThat(response.senderId()).isEqualTo(SENDER_ID);
    assertThat(response.content()).isEqualTo("안녕하세요");
    verify(chatRoomRepository).advanceLastMessage(ROOM_ID, MESSAGE_ID);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("SYSTEM 타입 송신은 INVALID_MESSAGE로 거부되고 저장하지 않는다")
  void send_whenSystemType_thenRejects() {
    // given
    ChatRoom room = activeRoom();
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID))
        .willReturn(membership(SENDER_ID, 1L, room));
    ChatMessageSendRequest request = new ChatMessageSendRequest(ChatMessageType.SYSTEM, "x");

    // when & then
    assertThatThrownBy(() -> chatMessageService.send(ROOM_ID, SENDER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.INVALID_MESSAGE.getCode());
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  @DisplayName("종료된 방 송신은 reader의 ROOM_CLOSED 예외를 그대로 전파한다")
  void send_whenRoomClosed_thenPropagates() {
    // given
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID))
        .willThrow(BaseException.from(ChatErrorCode.ROOM_CLOSED));
    ChatMessageSendRequest request = new ChatMessageSendRequest(ChatMessageType.TEXT, "안녕");

    // when & then
    assertThatThrownBy(() -> chatMessageService.send(ROOM_ID, SENDER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
    verify(chatMessageRepository, never()).save(any());
  }

  // ---------- 사진 공개 단계 카운트(이슈 #79) ----------

  @Test
  @DisplayName("유효 메시지로 양방향 min이 임계값에 도달하면 단계가 오르고 ChatProgressAdvancedEvent를 발행한다")
  void send_whenValidTextReachesThreshold_thenAdvancesAndPublishes() {
    // given — 상대는 이미 10, 나는 9. 내 유효 메시지 1건으로 min(10,10)=10 → STEP_1.
    ChatRoom room = activeRoom();
    ChatRoomMember sender = membership(SENDER_ID, 1L, room);
    ReflectionTestUtils.setField(sender, "validMessageCount", 9);
    ChatRoomMember partner = membership(PARTNER_ID, 2L, room);
    ReflectionTestUtils.setField(partner, "validMessageCount", 10);
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(sender);
    givenSavedMessage();
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
        .willReturn(List.of(sender, partner));

    // when
    chatMessageService.send(ROOM_ID, SENDER_ID, text("충분히 긴 메시지"));

    // then
    assertThat(sender.getValidMessageCount()).isEqualTo(10);
    assertThat(room.getProgressStatus()).isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    ArgumentCaptor<ChatProgressAdvancedEvent> captor =
        ArgumentCaptor.forClass(ChatProgressAdvancedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().roomId()).isEqualTo(ROOM_ID);
    assertThat(captor.getValue().progressStatus())
        .isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
  }

  @Test
  @DisplayName("trim 후 4글자 미만 TEXT는 카운트하지 않는다(짧은 메시지는 저장만)")
  void send_whenShortText_thenNotCounted() {
    sendAndAssertNotCounted(activeRoom(), text("안녕"));
  }

  @Test
  @DisplayName("IMAGE 메시지는 카운트하지 않는다(TEXT만 인정)")
  void send_whenImage_thenNotCounted() {
    ChatMessageSendRequest image =
        new ChatMessageSendRequest(ChatMessageType.IMAGE, "https://cdn/image.jpg");
    sendAndAssertNotCounted(activeRoom(), image);
  }

  @Test
  @DisplayName("직전 유효 카운트 발신자와 같은 발신자의 연속 메시지는 카운트하지 않는다(교대 발화)")
  void send_whenSameSenderConsecutive_thenNotCounted() {
    ChatRoom room = activeRoom();
    // 직전 유효 카운트 발신자가 나 자신이면 연속이라 카운트 제외.
    room.recordValidSender(SENDER_ID);
    sendAndAssertNotCounted(room, text("충분히 긴 메시지"));
  }

  @Test
  @DisplayName("발신자별 디바운스(2초) 이내 메시지는 카운트하지 않는다")
  void send_whenWithinDebounce_thenNotCounted() {
    ChatRoom room = activeRoom();
    ChatRoomMember sender = membership(SENDER_ID, 1L, room);
    // 마지막 유효 카운트가 '지금'이면 2초가 지나지 않아 카운트 제외.
    ReflectionTestUtils.setField(sender, "lastValidCountedAt", LocalDateTime.now(clock));
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(sender);
    givenSavedMessage();

    chatMessageService.send(ROOM_ID, SENDER_ID, text("충분히 긴 메시지"));

    assertThat(sender.getValidMessageCount()).isZero();
    verify(chatRoomMemberRepository, never()).findAllByRoomId(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------- markAsRead ----------

  @Test
  @DisplayName("그 방에 실제로 존재하는 메시지 id면 조건부 UPDATE로 읽음 커서를 전진시키고 그 값을 반환한다")
  void markAsRead_whenMessageInRoom_thenAdvancesLastRead() {
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID))
        .willReturn(membership(SENDER_ID, 1L, activeRoom()));
    given(chatMessageRepository.existsByIdAndChatRoom_Id(7L, ROOM_ID)).willReturn(true);
    given(chatRoomMemberRepository.findLastReadMessageId(ROOM_ID, SENDER_ID))
        .willReturn(Optional.of(7L));

    Long effectiveCursor = chatMessageService.markAsRead(ROOM_ID, SENDER_ID, 7L);

    verify(chatRoomMemberRepository).advanceLastReadMessage(ROOM_ID, SENDER_ID, 7L);
    assertThat(effectiveCursor).isEqualTo(7L);
  }

  @Test
  @DisplayName("이미 더 큰 커서가 있으면(조건부 UPDATE no-op) 역행하지 않고 기존 커서를 반환한다")
  void markAsRead_whenBehindCurrent_thenKeepsLargerCursor() {
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID))
        .willReturn(membership(SENDER_ID, 1L, activeRoom()));
    given(chatMessageRepository.existsByIdAndChatRoom_Id(5L, ROOM_ID)).willReturn(true);
    given(chatRoomMemberRepository.advanceLastReadMessage(ROOM_ID, SENDER_ID, 5L)).willReturn(0);
    given(chatRoomMemberRepository.findLastReadMessageId(ROOM_ID, SENDER_ID))
        .willReturn(Optional.of(9L));

    Long effectiveCursor = chatMessageService.markAsRead(ROOM_ID, SENDER_ID, 5L);

    assertThat(effectiveCursor).isEqualTo(9L);
  }

  @Test
  @DisplayName("방에 없는 커서(예: 조작된 큰 id)는 INVALID_MESSAGE로 거부되고 커서를 전진시키지 않는다")
  void markAsRead_whenMessageNotInRoom_thenRejects() {
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID))
        .willReturn(membership(SENDER_ID, 1L, activeRoom()));
    given(chatMessageRepository.existsByIdAndChatRoom_Id(Long.MAX_VALUE, ROOM_ID)).willReturn(false);

    assertThatThrownBy(() -> chatMessageService.markAsRead(ROOM_ID, SENDER_ID, Long.MAX_VALUE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.INVALID_MESSAGE.getCode());
    verify(chatRoomMemberRepository, never()).advanceLastReadMessage(anyLong(), anyLong(), anyLong());
  }

  // ---------- fixtures ----------

  /** 송신해도 유효 카운트되지 않음을 검증한다(카운트 0, 상대 조회·이벤트 없음). */
  private void sendAndAssertNotCounted(ChatRoom room, ChatMessageSendRequest request) {
    ChatRoomMember sender = membership(SENDER_ID, 1L, room);
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(sender);
    givenSavedMessage();

    chatMessageService.send(ROOM_ID, SENDER_ID, request);

    assertThat(sender.getValidMessageCount()).isZero();
    verify(chatRoomMemberRepository, never()).findAllByRoomId(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  private void givenSavedMessage() {
    given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(invocation -> {
      ChatMessage saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", MESSAGE_ID);
      return saved;
    });
  }

  private ChatMessageSendRequest text(String content) {
    return new ChatMessageSendRequest(ChatMessageType.TEXT, content);
  }

  private ChatRoom activeRoom() {
    ChatRoom room = ChatRoom.createOnMatched(SENDER_ID, PARTNER_ID);
    ReflectionTestUtils.setField(room, "id", ROOM_ID);
    return room;
  }

  private ChatRoomMember membership(Long memberId, Long rowId, ChatRoom room) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-" + memberId, "name", "member" + memberId + "@example.com", null);
    ReflectionTestUtils.setField(member, "id", memberId);
    ChatRoomMember crm = ChatRoomMember.join(room, member);
    ReflectionTestUtils.setField(crm, "id", rowId);
    return crm;
  }
}
