package com.blursome.blursome.chat.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 읽음 위치 갱신 요청(STOMP {@code /app/rooms/{roomId}/read}, 설계 §7-4).
 *
 * <p>{@code lastReadMessageId}까지 읽은 것으로 표시한다. 읽은 위치는 뒤로 가지 않고 전진만 한다
 * ({@link com.blursome.blursome.chat.domain.ChatRoomMember#readUpTo}).
 */
public record ChatReadRequest(
    @NotNull Long lastReadMessageId
) {

}
