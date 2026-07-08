package com.blursome.blursome.chat.event;

import com.blursome.blursome.chat.dto.response.ChatMessageResponse;

/**
 * 저장된 채팅 메시지를 {@code /topic/rooms/{roomId}}로 브로드캐스트하라고 알리는 도메인 이벤트(이슈 #85).
 *
 * <p>사람이 보낸 {@code TEXT}/{@code IMAGE}와 단계 상승으로 기록되는 {@code SYSTEM} 메시지를 <b>같은 경로
 * (트랜잭션 이벤트)</b>로 내보내 실시간 전송 순서를 발행 순서와 일치시킨다. 송신 트랜잭션에서 트리거 메시지를
 * 먼저 발행하고 SYSTEM 메시지를 뒤에 발행하므로, {@code AFTER_COMMIT}에서 트리거 → 안내 순서로 전송된다.
 * (컨트롤러가 커밋 후 직접 브로드캐스트하던 기존 방식은 {@code AFTER_COMMIT} 리스너보다 늦게 실행돼 순서가
 * 역전됐다 — 이를 제거하고 브로드캐스트를 모두 커밋 이후 이벤트로 통일한다.)
 *
 * <p>리스너는 커밋 이후가 안전하므로 {@code @TransactionalEventListener(AFTER_COMMIT)}로 받는다(롤백 시 미전송).
 */
public record ChatMessageBroadcastEvent(Long roomId, ChatMessageResponse message) {
}
