package com.blursome.blursome.chat.event;

import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;

/**
 * 양측 동의로 방의 진행 단계가 한 칸 올랐을 때 발행되는 도메인 이벤트(설계 §7-5).
 *
 * <p>단계 상승 사실을 트랜잭션 경계 밖으로 알리는 seam이다. WebSocket 단계에서 이 이벤트를 구독하는 리스너가
 * {@code /topic/rooms/{roomId}}로 단계 변경({@code PROGRESS_CHANGED})을 브로드캐스트한다. 같은 단계 상승으로
 * 기록되는 SYSTEM 안내 메시지는 별도의 {@link ChatMessageBroadcastEvent}로 먼저 발행돼 타임라인에 남는다
 * (이슈 #85). 메시지 전송은 커밋 이후가 안전하므로, 리스너는 {@code @TransactionalEventListener(AFTER_COMMIT)}로
 * 받는 것을 권장한다.
 */
public record ChatProgressAdvancedEvent(Long roomId, ChatRoomProgressStatus progressStatus) {
}
