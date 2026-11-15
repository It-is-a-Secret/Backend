package com.blursome.blursome.notification.event;

import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationType;
import com.blursome.blursome.chat.event.ChatRoomNotificationEvent;
import com.blursome.blursome.notification.config.PushAsyncConfig;
import com.blursome.blursome.notification.service.ChatPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 채팅 개인 알림 이벤트를 받아 수신자 기기로 Web Push를 보낸다(이슈 #121).
 *
 * <p>STOMP 개인 큐 리스너({@code ChatRoomNotificationListener})와 같은 이벤트를 받되, {@code NEW_MESSAGE}만 처리한다.
 * 첫 메시지로 방이 열리면 같은 메시지에 대해 {@code NEW_ROOM}과 {@code NEW_MESSAGE}가 함께 발행되는데, {@code NEW_ROOM}은
 * 무시해 메시지 하나당 기기마다 푸시가 한 번만 가게 한다.
 *
 * <p>{@code AFTER_COMMIT}으로 받아 커밋된 메시지만 보내고(롤백 시 미발송), 외부 HTTP 호출이 채팅 송신 스레드를 막지
 * 않도록 전용 실행기에서 비동기로 보낸다. 실패해도 채팅 흐름에는 영향이 없도록 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPushListener {

  private final ChatPushService chatPushService;

  @Async(PushAsyncConfig.PUSH_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRoomNotification(ChatRoomNotificationEvent event) {
    ChatRoomNotificationResponse notification = event.notification();
    if (notification.type() != ChatRoomNotificationType.NEW_MESSAGE) {
      return;
    }
    try {
      chatPushService.sendNewMessage(event.targetMemberId(), notification.partnerId(),
          notification.message());
    } catch (RuntimeException e) {
      log.warn("채팅 푸시 발송 실패. recipientId={}, roomId={}", event.targetMemberId(),
          notification.roomId(), e);
    }
  }
}
