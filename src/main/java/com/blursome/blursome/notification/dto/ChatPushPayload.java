package com.blursome.blursome.notification.dto;

import com.blursome.blursome.chat.domain.ChatMessageType;

/**
 * 채팅 푸시 페이로드(이슈 #121). 서비스 워커가 {@code title}·{@code body}로 알림을 띄우고 클릭하면 {@code url}로 이동한다.
 *
 * <p>암호화 후 4KB 제한이 있어 메시지 전체가 아니라 앞부분만 싣는다.
 *
 * @param type 항상 {@code NEW_MESSAGE}. 첫 메시지로 방이 열린 경우도 푸시는 이 타입 하나만 보낸다.
 * @param roomId 채팅방 id
 * @param messageId 메시지 id
 * @param title 발신자 닉네임(조회 실패 시 {@code BlurSome})
 * @param body 텍스트 앞부분 최대 80자. 이미지는 {@code 사진을 보냈어요}
 * @param url 알림 클릭 시 이동할 프론트 경로({@code /chat/{roomId}})
 */
public record ChatPushPayload(
    String type,
    Long roomId,
    Long messageId,
    String title,
    String body,
    String url
) {

  public static final String TYPE_NEW_MESSAGE = "NEW_MESSAGE";
  public static final String DEFAULT_TITLE = "BlurSome";
  public static final String IMAGE_BODY = "사진을 보냈어요";
  public static final String FALLBACK_BODY = "새 메시지가 도착했어요";
  public static final int MAX_BODY_LENGTH = 80;
  private static final String ELLIPSIS = "…";

  public static ChatPushPayload newMessage(Long roomId, Long messageId, String senderNickname,
      ChatMessageType messageType, String content) {
    String title = senderNickname == null || senderNickname.isBlank()
        ? DEFAULT_TITLE : senderNickname;
    return new ChatPushPayload(TYPE_NEW_MESSAGE, roomId, messageId, title,
        preview(messageType, content), "/chat/" + roomId);
  }

  /** 텍스트는 공백을 한 칸으로 줄인 뒤 앞부분 최대 80자(코드 포인트 기준, 넘으면 말줄임표). 이미지는 고정 문구. */
  static String preview(ChatMessageType messageType, String content) {
    if (messageType == ChatMessageType.IMAGE) {
      return IMAGE_BODY;
    }
    if (content == null || content.isBlank()) {
      return FALLBACK_BODY;
    }
    String normalized = content.strip().replaceAll("\\s+", " ");
    if (normalized.codePointCount(0, normalized.length()) <= MAX_BODY_LENGTH) {
      return normalized;
    }
    int end = normalized.offsetByCodePoints(0, MAX_BODY_LENGTH);
    return normalized.substring(0, end) + ELLIPSIS;
  }
}
