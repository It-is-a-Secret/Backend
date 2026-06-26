package com.blursome.blursome.chat.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 메시지 브로드캐스트 이벤트({@link ChatMessageBroadcastEvent})를 구독해 {@code /topic/rooms/{roomId}}로
 * 전송한다(이슈 #85). 사람이 보낸 {@code TEXT}/{@code IMAGE}와 단계 상승 {@code SYSTEM} 메시지를 모두 같은
 * 형태({@code ChatMessageResponse})로 내보낸다.
 *
 * <p>{@code AFTER_COMMIT} 단계로 받아 메시지가 DB에 실제 커밋된 뒤에만 전송한다(롤백 시 전송 안 함). 한 송신
 * 트랜잭션에서 발행된 여러 이벤트는 발행 순서대로 처리되므로, 트리거 메시지가 안내 SYSTEM 메시지보다 먼저
 * 전송된다(클라이언트는 {@code messageId}로 삽입·정렬한다).
 */
@Component
@RequiredArgsConstructor
public class ChatMessageBroadcastListener {

  private final SimpMessagingTemplate messagingTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMessageBroadcast(ChatMessageBroadcastEvent event) {
    messagingTemplate.convertAndSend("/topic/rooms/" + event.roomId(), event.message());
  }
}
