package com.blursome.blursome.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

  private static final Long ROOM_ID = 10L;
  private static final Long SENDER_ID = 100L;
  private static final Long MESSAGE_ID = 5L;

  @Mock
  private ChatMessageRepository chatMessageRepository;

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatRoomMembershipReader membershipReader;

  @InjectMocks
  private ChatMessageService chatMessageService;

  @Test
  @DisplayName("TEXT 메시지를 저장하고 방 미리보기를 원자적으로 전진시킨 응답을 반환한다")
  void send_whenText_thenSavesAndAdvancesPreview() {
    // given
    ChatRoom room = activeRoom();
    ChatRoomMember membership = membership(room);
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(membership);
    given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(invocation -> {
      ChatMessage saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", MESSAGE_ID);
      return saved;
    });
    ChatMessageSendRequest request = new ChatMessageSendRequest(ChatMessageType.TEXT, "안녕하세요");

    // when
    ChatMessageResponse response = chatMessageService.send(ROOM_ID, SENDER_ID, request);

    // then
    assertThat(response.messageId()).isEqualTo(MESSAGE_ID);
    assertThat(response.senderId()).isEqualTo(SENDER_ID);
    assertThat(response.type()).isEqualTo(ChatMessageType.TEXT);
    assertThat(response.content()).isEqualTo("안녕하세요");
    verify(chatRoomRepository).advanceLastMessage(ROOM_ID, MESSAGE_ID);
  }

  @Test
  @DisplayName("SYSTEM 타입 송신은 INVALID_MESSAGE로 거부되고 저장하지 않는다")
  void send_whenSystemType_thenRejects() {
    // given
    ChatRoom room = activeRoom();
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(membership(room));
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

  @Test
  @DisplayName("그 방에 실제로 존재하는 메시지 id면 읽음 위치를 전진시킨다")
  void markAsRead_whenMessageInRoom_thenAdvancesLastRead() {
    // given
    ChatRoomMember membership = membership(activeRoom());
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(membership);
    given(chatMessageRepository.existsByIdAndChatRoom_Id(7L, ROOM_ID)).willReturn(true);

    // when
    chatMessageService.markAsRead(ROOM_ID, SENDER_ID, 7L);

    // then
    assertThat(membership.getLastReadMessageId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("방에 없는 커서(예: 조작된 큰 id)는 INVALID_MESSAGE로 거부되고 읽음 위치가 바뀌지 않는다")
  void markAsRead_whenMessageNotInRoom_thenRejects() {
    // given
    ChatRoomMember membership = membership(activeRoom());
    given(membershipReader.getWritableMembership(ROOM_ID, SENDER_ID)).willReturn(membership);
    given(chatMessageRepository.existsByIdAndChatRoom_Id(Long.MAX_VALUE, ROOM_ID)).willReturn(false);

    // when & then
    assertThatThrownBy(() -> chatMessageService.markAsRead(ROOM_ID, SENDER_ID, Long.MAX_VALUE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.INVALID_MESSAGE.getCode());
    assertThat(membership.getLastReadMessageId()).isNull();
  }

  // ---------- fixtures ----------

  private ChatRoom activeRoom() {
    ChatRoom room = ChatRoom.createOnMatched(SENDER_ID, 200L);
    ReflectionTestUtils.setField(room, "id", ROOM_ID);
    return room;
  }

  private ChatRoomMember membership(ChatRoom room) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "name", "member@example.com", null);
    ReflectionTestUtils.setField(member, "id", SENDER_ID);
    ChatRoomMember crm = ChatRoomMember.join(room, member);
    ReflectionTestUtils.setField(crm, "id", 1L);
    ReflectionTestUtils.setField(crm, "agreedProgressStatus", ChatRoomProgressStatus.MATCHED);
    return crm;
  }
}
