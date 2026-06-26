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

  // 마지막으로 유효 카운트된 발신자 회원 id(이슈 #79). "같은 발신자의 연속 메시지는 카운트하지 않음"(교대 발화
  // 강제) 판정에 쓰는 방 단위 상태다. 아직 유효 카운트가 없으면 null.
  @Column(name = "last_valid_counted_sender_id")
  private Long lastValidCountedSenderId;

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

  /**
   * 신고 누적으로 방을 신고됨({@code REPORTED})으로 전환한다(ACTIVE → REPORTED, 멱등).
   * REPORTED는 {@link #isActive()}가 아니므로 송신·조회가 동결된다(운영자 검토 대기).
   * 이미 종료(CLOSED)/신고(REPORTED)된 방은 그대로 둔다.
   */
  public void markReported() {
    if (this.roomStatus == ChatRoomStatus.ACTIVE) {
      this.roomStatus = ChatRoomStatus.REPORTED;
    }
  }

  /**
   * 차단으로 방을 동결({@code BLOCKED})한다(ACTIVE → BLOCKED, 멱등, 이슈 #77). {@code BLOCKED}는
   * {@link #isActive()}가 아니므로 양쪽 참여자의 송신·원본 공개가 막히고 유효 메시지 카운트·사진 공개 단계도
   * 진행되지 않는다. 차단은 해제 가능하므로 페어 키({@code activePairKey})는 비우지 않는다 —
   * {@link #close()}와 달리 동결은 가역이라 해제 시 같은 방을 ACTIVE로 되살린다. 이미 종료(CLOSED)/신고
   * (REPORTED)된 방은 그대로 둔다.
   */
  public void markBlocked() {
    if (this.roomStatus == ChatRoomStatus.ACTIVE) {
      this.roomStatus = ChatRoomStatus.BLOCKED;
    }
  }

  /**
   * 차단 해제로 동결을 푼다(BLOCKED → ACTIVE, 멱등, 이슈 #77). 양방향 차단이 모두 해제됐을 때만 호출된다
   * (호출 측이 잔존 차단 부재를 보장). 동결({@code BLOCKED})이 아닌 방은 그대로 둔다 — 특히 종료(CLOSED)는
   * 비가역(terminal)이라 되살리지 않으며, 신고(REPORTED) 동결도 차단 해제로 풀지 않는다.
   */
  public void unblockToActive() {
    if (this.roomStatus == ChatRoomStatus.BLOCKED) {
      this.roomStatus = ChatRoomStatus.ACTIVE;
    }
  }

  public boolean isActive() {
    return this.roomStatus == ChatRoomStatus.ACTIVE;
  }

  /**
   * 직전에 유효 카운트된 발신자와 다른 발신자인지 여부(교대 발화 강제, 이슈 #79). 아직 유효 카운트가 없으면
   * (첫 유효 메시지) 항상 {@code true}다. 같은 발신자의 연속 메시지는 카운트하지 않기 위한 방 단위 판정이다.
   */
  public boolean isAlternatingSender(Long senderId) {
    return this.lastValidCountedSenderId == null
        || !this.lastValidCountedSenderId.equals(senderId);
  }

  /** 마지막 유효 카운트 발신자를 기록한다(다음 메시지의 교대 발화 판정 기준). */
  public void recordValidSender(Long senderId) {
    this.lastValidCountedSenderId = senderId;
  }

  /**
   * 사진 공개 단계를 {@code target}으로 전진시킨다(이슈 #79). 단조 증가만 허용하므로 현재보다 더 진행된 단계일
   * 때만 갱신하고, 같거나 더 낮은 단계 요청은 무시한다(후퇴 방지). 유효 메시지 1건은 {@code min(A, B)}를 최대 1만
   * 올리므로 보통 한 칸 상승이지만, 호출 측은 변경 여부만 보고 이벤트 발행을 결정한다.
   *
   * @return 단계가 실제로 올랐으면 {@code true}, 변화 없으면 {@code false}
   */
  public boolean advanceProgressTo(ChatRoomProgressStatus target) {
    if (target == this.progressStatus || !target.isAtLeast(this.progressStatus)) {
      return false;
    }
    this.progressStatus = target;
    return true;
  }
}
