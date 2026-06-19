package com.blursome.blursome.chat.dto.request;

import com.blursome.blursome.chat.domain.ChatMessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 실시간 메시지 송신 요청(STOMP {@code /app/rooms/{roomId}/send}, 설계 §6-4).
 *
 * <p>발신자({@code senderId})는 클라이언트 입력이 아니라 STOMP principal에서 결정하므로 본문에 담지 않는다(설계 §9 발신자 위조 방지).
 * {@code type}은 사람이 보내는 {@code TEXT}/{@code IMAGE}만 허용하며 {@code SYSTEM}은 서버 전용이라 서비스에서 거부한다.
 */
public record ChatMessageSendRequest(
    @NotNull ChatMessageType type,
    @NotBlank String content
) {

}
