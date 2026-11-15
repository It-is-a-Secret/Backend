package com.blursome.blursome.notification.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;
import com.blursome.blursome.chat.event.ChatRoomNotificationEvent;
import com.blursome.blursome.notification.service.ChatPushService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatPushListenerTest {

  private static final Long ROOM_ID = 10L;
  private static final Long SENDER_ID = 100L;
  private static final Long RECIPIENT_ID = 200L;

  @Mock
  private ChatPushService chatPushService;

  @InjectMocks
  private ChatPushListener listener;

  private final ChatMessageResponse message = new ChatMessageResponse(
      5L, ROOM_ID, SENDER_ID, ChatMessageType.TEXT, "안녕하세요", LocalDateTime.now());

  @Test
  @DisplayName("NEW_MESSAGE는 수신자·발신자·메시지로 푸시를 보낸다")
  void onRoomNotification_whenNewMessage_thenSendsPush() {
    listener.onRoomNotification(new ChatRoomNotificationEvent(RECIPIENT_ID,
        ChatRoomNotificationResponse.newMessage(ROOM_ID, SENDER_ID, message)));

    verify(chatPushService).sendNewMessage(RECIPIENT_ID, SENDER_ID, message);
  }

  @Test
  @DisplayName("첫 메시지의 NEW_ROOM은 무시해 같은 메시지로 푸시가 두 번 가지 않는다")
  void onRoomNotification_whenNewRoom_thenIgnored() {
    listener.onRoomNotification(new ChatRoomNotificationEvent(RECIPIENT_ID,
        ChatRoomNotificationResponse.newRoom(ROOM_ID, SENDER_ID, "블러곰", message)));

    verify(chatPushService, never()).sendNewMessage(any(), any(), any());
  }

  @Test
  @DisplayName("발송 중 예외가 나도 전파하지 않는다")
  void onRoomNotification_whenSendFails_thenSwallows() {
    willThrow(new IllegalStateException("boom")).given(chatPushService)
        .sendNewMessage(any(), any(), any());

    listener.onRoomNotification(new ChatRoomNotificationEvent(RECIPIENT_ID,
        ChatRoomNotificationResponse.newMessage(ROOM_ID, SENDER_ID, message)));
  }
}
