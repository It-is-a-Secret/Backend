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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "chat_room_member",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_room_member",
        columnNames = {"chat_room_id", "member_id"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private LocalDateTime joinedAt;

  @Column
  // 나갔던 회원의 "재입장"은 새 행 생성이 아니라 기존 행의 {@code leftAt} 을 다시 {@code null}
  private LocalDateTime leftAt;

  @Column
  private Long lastReadMessageId;

  // 이 멤버가 동의한 진행 단계. 양쪽 멤버가 같은 단계에 동의하면 방의 progressStatus가 올라간다.
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ChatRoomProgressStatus agreedProgressStatus;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "chat_room_id", nullable = false)
  private ChatRoom chatRoom;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @Builder(access = AccessLevel.PRIVATE)
  private ChatRoomMember(
      ChatRoom chatRoom,
      Member member,
      LocalDateTime joinedAt,
      ChatRoomProgressStatus agreedProgressStatus
  ) {
    this.chatRoom = chatRoom;
    this.member = member;
    this.joinedAt = joinedAt;
    this.agreedProgressStatus = agreedProgressStatus;
  }

  public static ChatRoomMember join(ChatRoom chatRoom, Member member) {
    return ChatRoomMember.builder()
        .chatRoom(chatRoom)
        .member(member)
        .joinedAt(LocalDateTime.now())
        .agreedProgressStatus(ChatRoomProgressStatus.MATCHED)
        .build();
  }

  /** 채팅방을 나간다(영구). 이미 나간 상태면 예외. 재입장은 제공하지 않는다. */
  public void leave() {
    if (this.leftAt != null) {
      throw new IllegalStateException("이미 나간 참여자입니다.");
    }
    this.leftAt = LocalDateTime.now();
  }

  public boolean hasLeft() {
    return this.leftAt != null;
  }

  /**
   * 다음 단계 공개에 동의한다. 동의는 단조 증가만 허용(되돌리기 비허용)하므로,
   * 현재 동의 단계보다 더 진행된 단계만 받아들인다.
   */
  public void agreeProgress(ChatRoomProgressStatus target) {
    if (this.agreedProgressStatus.isAtLeast(target)) {
      throw new IllegalArgumentException(
          "동의 단계는 되돌릴 수 없습니다. 현재=" + this.agreedProgressStatus + ", 요청=" + target);
    }
    this.agreedProgressStatus = target;
  }

  /** 읽은 위치를 갱신한다. 뒤로 가지 않도록 더 큰 메시지 id일 때만 전진시킨다. */
  public void readUpTo(Long messageId) {
    if (messageId == null) {
      return;
    }
    if (this.lastReadMessageId == null || messageId > this.lastReadMessageId) {
      this.lastReadMessageId = messageId;
    }
  }
}
