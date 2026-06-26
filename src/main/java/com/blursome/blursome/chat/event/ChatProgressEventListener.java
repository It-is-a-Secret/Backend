package com.blursome.blursome.chat.event;

import com.blursome.blursome.chat.dto.response.ChatProgressChangedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 단계 상승 이벤트({@link ChatProgressAdvancedEvent})를 구독해 {@code /topic/rooms/{roomId}}로 {@code PROGRESS_CHANGED}를
 * 브로드캐스트한다(설계 §7-5). 프론트의 진행 상태 갱신용 신호다.
 *
 * <p>{@code AFTER_COMMIT} 단계로 받아 단계 변경이 DB에 실제 커밋된 뒤에만 발행한다(롤백 시 발송 안 함). 같은 단계
 * 상승으로 기록되는 SYSTEM 안내 메시지는 {@link ChatMessageBroadcastEvent}가 이 이벤트보다 먼저 발행돼
 * ({@link ChatMessageBroadcastListener}) 타임라인에 트리거 메시지 → 안내 메시지 순서로 전송된다(이슈 #85).
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
