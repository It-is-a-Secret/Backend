package com.blursome.blursome.chat.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 개인 알림 이벤트({@link ChatRoomNotificationEvent})를 구독해 대상 회원의 개인 큐로 전송한다(이슈 #88).
 * {@code convertAndSendToUser(memberId, "/queue/rooms", payload)}는 Spring이 세션 principal로 라우팅해
 * 클라이언트의 {@code /user/queue/rooms} 구독으로 내려간다 — STOMP principal name이 곧 memberId이므로
 * ({@code StompAuthChannelInterceptor}) 회원 id를 그대로 라우팅 키로 쓴다.
 *
 * <p>방 토픽 브로드캐스트({@link ChatMessageBroadcastListener})와 동일하게 {@code AFTER_COMMIT} 단계로 받아
 * 새 방·메시지가 DB에 실제 커밋된 뒤에만 전송한다(롤백 시 미전송). 대상 회원이 지금 접속해 있지 않으면 개인
 * 큐 구독이 없어 메시지는 그대로 버려지고(설계상 오프라인 보관 없음), 다음 접속 시 방 목록 조회로 동기화된다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomNotificationListener {

  /** 개인 알림 destination(클라이언트는 {@code setUserDestinationPrefix("/user")}로 {@code /user/queue/rooms} 구독). */
  private static final String DESTINATION = "/queue/rooms";

  private final SimpMessagingTemplate messagingTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRoomNotification(ChatRoomNotificationEvent event) {
    messagingTemplate.convertAndSendToUser(
        String.valueOf(event.targetMemberId()), DESTINATION, event.notification());
  }
}
