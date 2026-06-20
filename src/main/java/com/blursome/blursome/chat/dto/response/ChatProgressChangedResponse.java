package com.blursome.blursome.chat.dto.response;

import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.event.ChatProgressAdvancedEvent;

/**
 * 양측 동의로 방의 진행 단계가 올랐을 때 {@code /topic/rooms/{roomId}}로 브로드캐스트되는 단계 변경 알림(설계 §7-5).
 *
 * <p>같은 토픽으로 흐르는 {@link ChatMessageResponse}와 구분되도록 {@code eventType}을 명시한다(클라이언트는 이 필드로 분기).
 */
public record ChatProgressChangedResponse(
    String eventType,
    Long roomId,
    ChatRoomProgressStatus progressStatus
) {

  private static final String EVENT_TYPE = "PROGRESS_CHANGED";

  public static ChatProgressChangedResponse from(ChatProgressAdvancedEvent event) {
    return new ChatProgressChangedResponse(EVENT_TYPE, event.roomId(), event.progressStatus());
  }
}
