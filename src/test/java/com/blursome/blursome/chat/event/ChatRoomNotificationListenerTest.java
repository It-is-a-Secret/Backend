package com.blursome.blursome.chat.event;

import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ChatRoomNotificationListenerTest {

  private static final Long ROOM_ID = 10L;
  private static final Long SENDER_ID = 100L;
  private static final Long RECIPIENT_ID = 200L;

  @Mock
  private SimpMessagingTemplate messagingTemplate;

  @InjectMocks
  private ChatRoomNotificationListener listener;

  @Test
  @DisplayName("대상 회원 id를 principal name으로 써서 개인 큐(/queue/rooms)로 페이로드를 그대로 전송한다")
  void onRoomNotification_routesToUserQueueByMemberId() {
    ChatMessageResponse message = new ChatMessageResponse(
        5L, ROOM_ID, SENDER_ID, ChatMessageType.TEXT, "안녕하세요", LocalDateTime.now());
    ChatRoomNotificationResponse payload =
        ChatRoomNotificationResponse.newMessage(ROOM_ID, SENDER_ID, message);

    listener.onRoomNotification(new ChatRoomNotificationEvent(RECIPIENT_ID, payload));

    // convertAndSendToUser(principalName=memberId, "/queue/rooms", payload) — /user prefix는 Spring이 붙인다.
    verify(messagingTemplate)
        .convertAndSendToUser(String.valueOf(RECIPIENT_ID), "/queue/rooms", payload);
  }
}
