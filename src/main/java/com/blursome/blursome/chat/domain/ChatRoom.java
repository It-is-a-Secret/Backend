package com.blursome.blursome.chat.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "chat_room")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ChatRoomStatus roomStatus;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ChatRoomProgressStatus progressStatus;

  // 마지막 메세지 미리보기
  @Column
  private Long lastMessageId;

  @Builder(access = AccessLevel.PRIVATE)
  private ChatRoom(
      ChatRoomStatus roomStatus,
      ChatRoomProgressStatus progressStatus,
      Long lastMessageId
  ) {
    this.roomStatus = roomStatus;
    this.progressStatus = progressStatus;
    this.lastMessageId = lastMessageId;
  }

  public static ChatRoom createOnMatched() {
    return ChatRoom.builder()
        .roomStatus(ChatRoomStatus.ACTIVE)
        .progressStatus(ChatRoomProgressStatus.MATCHED)
        .build();
  }

  /** 마지막 메시지 미리보기 id를 갱신한다(메시지 저장 시 호출). */
  public void updateLastMessage(Long messageId) {
    this.lastMessageId = messageId;
  }

  /** 방을 종료한다. 1:1이므로 한쪽 나가기로도 호출된다. 이미 종료된 경우 멱등(no-op). */
  public void close() {
    if (this.roomStatus == ChatRoomStatus.CLOSED) {
      return;
    }
    this.roomStatus = ChatRoomStatus.CLOSED;
  }

  public boolean isActive() {
    return this.roomStatus == ChatRoomStatus.ACTIVE;
  }

  /**
   * 두 참여자의 동의 단계가 모두 다음 단계 이상이면 방 단계를 한 단계 올린다.
   *
   * @return 단계가 올랐으면 {@code true}, 아니면 {@code false}
   */
  public boolean advanceProgressIfBothAgreed(ChatRoomMember a, ChatRoomMember b) {
    if (this.progressStatus.isLast()) {
      return false;
    }
    ChatRoomProgressStatus next = this.progressStatus.next();
    boolean bothAgreed = a.getAgreedProgressStatus().isAtLeast(next)
        && b.getAgreedProgressStatus().isAtLeast(next);
    if (!bothAgreed) {
      return false;
    }
    this.progressStatus = next;
    return true;
  }
}
