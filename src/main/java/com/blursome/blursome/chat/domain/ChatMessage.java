package com.blursome.blursome.chat.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "chat_message",
    indexes = @Index(name = "idx_chat_message_room", columnList = "chat_room_id, id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "chat_room_id", nullable = false)
  private ChatRoom chatRoom;

  // SYSTEM 메시지인 경우 null
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sender_id")
  private Member sender;

  @Lob
  @Column(nullable = false)
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ChatMessageType type;

  @Builder(access = AccessLevel.PRIVATE)
  private ChatMessage(ChatRoom chatRoom, Member sender, String content, ChatMessageType type) {
    this.chatRoom = chatRoom;
    this.sender = sender;
    this.content = content;
    this.type = type;
  }

  public static ChatMessage createTextMessage(ChatRoom chatRoom, Member sender, String content) {
    requireSender(sender);
    requireContent(content);
    return ChatMessage.builder()
        .chatRoom(chatRoom)
        .sender(sender)
        .content(content)
        .type(ChatMessageType.TEXT)
        .build();
  }

  public static ChatMessage createImageMessage(ChatRoom chatRoom, Member sender, String imageUrl) {
    requireSender(sender);
    requireContent(imageUrl);
    return ChatMessage.builder()
        .chatRoom(chatRoom)
        .sender(sender)
        //content : 업로드된 이미지의 URL(또는 식별자)을 저장한다.
        .content(imageUrl)
        .type(ChatMessageType.IMAGE)
        .build();
  }

  /**
   * TEXT/IMAGE 메시지는 사람 발신자가 반드시 있어야 한다(sender=null은 SYSTEM 전용).
   */
  private static void requireSender(Member sender) {
    if (sender == null) {
      throw new IllegalArgumentException("TEXT/IMAGE 메시지에는 발신자가 필요합니다.");
    }
  }

  /**
   * 본문(텍스트/이미지 URL)은 비어 있을 수 없다.
   */
  private static void requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("메시지 본문은 비어 있을 수 없습니다.");
    }
  }


  public static ChatMessage createSystemMessage(ChatRoom chatRoom, String content) {
    requireContent(content);
    // sender 는 의도적으로 설정하지 않는다(SYSTEM = 사람 발신자 없음).
    return ChatMessage.builder()
        .chatRoom(chatRoom)
        .content(content)
        .type(ChatMessageType.SYSTEM)
        .build();
  }
}
