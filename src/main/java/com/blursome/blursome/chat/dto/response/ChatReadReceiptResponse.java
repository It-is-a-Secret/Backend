package com.blursome.blursome.chat.dto.response;

/**
 * 참여자가 읽음 위치를 전진시켰을 때 {@code /topic/rooms/{roomId}}로 브로드캐스트되는 읽음 확인 알림(설계 §7-4).
 *
 * <p>같은 토픽으로 흐르는 {@link ChatMessageResponse}·{@link ChatProgressChangedResponse}와 구분되도록
 * {@code eventType}을 명시한다(클라이언트는 이 필드로 분기). 상대 클라이언트는 {@code readerId}가 자신이 아닐 때,
 * 자신이 보낸 메시지 중 {@code 메시지 id <= lastReadMessageId}인 것을 "읽음"으로 표시하면 된다.
 */
public record ChatReadReceiptResponse(
    String eventType,
    Long roomId,
    Long readerId,
    Long lastReadMessageId
) {

  private static final String EVENT_TYPE = "READ";

  public static ChatReadReceiptResponse of(Long roomId, Long readerId, Long lastReadMessageId) {
    return new ChatReadReceiptResponse(EVENT_TYPE, roomId, readerId, lastReadMessageId);
  }
}
