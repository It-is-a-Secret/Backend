package com.blursome.blursome.chat.dto.response;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatMessageType;
import java.time.LocalDateTime;

/** 메시지 이력 조회 응답. SYSTEM 메시지는 {@code senderId}가 null이다. */
public record ChatMessageResponse(
    Long messageId,
    Long roomId,
    Long senderId,
    ChatMessageType type,
    String content,
    LocalDateTime createdAt
) {

  public static ChatMessageResponse from(ChatMessage message) {
    Long senderId = message.getSender() == null ? null : message.getSender().getId();
    return new ChatMessageResponse(
        message.getId(),
        message.getChatRoom().getId(),
        senderId,
        message.getType(),
        message.getContent(),
        message.getCreatedAt()
    );
  }
}
