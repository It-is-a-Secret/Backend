package com.blursome.blursome.chat.event;

import com.blursome.blursome.chat.dto.response.ChatProgressChangedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 단계 상승 이벤트({@link ChatProgressAdvancedEvent})를 구독해 {@code /topic/rooms/{roomId}}로 브로드캐스트한다(설계 §7-5).
 *
 * <p>{@code AFTER_COMMIT} 단계로 받아 단계 변경이 DB에 실제 커밋된 뒤에만 발행한다(롤백 시 발송 안 함).
 */
@Component
@RequiredArgsConstructor
public class ChatProgressEventListener {

  private final SimpMessagingTemplate messagingTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProgressAdvanced(ChatProgressAdvancedEvent event) {
    messagingTemplate.convertAndSend(
        "/topic/rooms/" + event.roomId(),
        ChatProgressChangedResponse.from(event));
  }
}
