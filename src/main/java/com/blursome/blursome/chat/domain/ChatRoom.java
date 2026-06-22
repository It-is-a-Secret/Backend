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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "chat_room",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_room_active_pair",
        columnNames = "active_pair_key"
    )
)
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

  // 두 참여 회원 id를 정렬해 만든 키. ACTIVE인 동안만 값을 가지며(종료 시 null),
  // 유니크 제약으로 "회원 쌍당 ACTIVE 방 1개"를 DB 레벨에서 보장한다(동시 개설 방지, 설계 §7-1).
  // 유니크 인덱스는 다중 null을 허용하므로 같은 페어의 CLOSED 방은 여러 개 존재할 수 있다.
  @Column(name = "active_pair_key", length = 40)
  private String activePairKey;

  @Builder(access = AccessLevel.PRIVATE)
  private ChatRoom(
      ChatRoomStatus roomStatus,
      ChatRoomProgressStatus progressStatus,
      Long lastMessageId,
      String activePairKey
  ) {
    this.roomStatus = roomStatus;
    this.progressStatus = progressStatus;
    this.lastMessageId = lastMessageId;
    this.activePairKey = activePairKey;
  }

  public static ChatRoom createOnMatched(Long memberAId, Long memberBId) {
    return ChatRoom.builder()
        .roomStatus(ChatRoomStatus.ACTIVE)
        .progressStatus(ChatRoomProgressStatus.MATCHED)
        .activePairKey(activePairKey(memberAId, memberBId))
        .build();
  }

  /** 두 회원 id를 정렬해 페어 키를 만든다(인자 순서와 무관하게 동일 키). */
  private static String activePairKey(Long memberAId, Long memberBId) {
    long low = Math.min(memberAId, memberBId);
    long high = Math.max(memberAId, memberBId);
    return low + "-" + high;
  }

  /** 방을 종료한다. 1:1이므로 한쪽 나가기로도 호출된다. 이미 종료된 경우 멱등(no-op). */
  public void close() {
    if (this.roomStatus == ChatRoomStatus.CLOSED) {
      return;
    }
    this.roomStatus = ChatRoomStatus.CLOSED;
    // 페어 키를 비워 유니크 제약을 풀어준다 → 같은 두 회원이 새 매칭으로 새 방을 열 수 있다.
    this.activePairKey = null;
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
