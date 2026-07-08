package com.blursome.blursome.chat.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Getter
@Table(
    name = "chat_room_member",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_room_member",
        columnNames = {"chat_room_id", "member_id"}
    ),
    // uk_chat_room_member의 선두 컬럼이 chat_room_id라 member_id 단독 조회
    // (내 채팅 목록·상대 정보: findActiveMembershipsWithRoom, findPartnerInfos)가 인덱스를 못 탄다.
    // member_id 보조 인덱스를 별도로 둬 풀스캔을 방지한다.
    indexes = @Index(
        name = "idx_chat_room_member_member",
        columnList = "member_id"
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
  // 채팅방 퇴장 시각. leave()로 한 번 세팅되면 영구 퇴장이며, 재입장은 제공하지 않는다(null로 되돌리지 않음).
  private LocalDateTime leftAt;

  @Column
  private Long lastReadMessageId;

  // 사진 공개 단계 판정을 위해 누적된 '유효 송신 이벤트' 수(이슈 #79). "현재 남은 메시지 수"가 아니라
  // 송신 시점에만 증가하는 단조 카운터이며, 사후 삭제로 되돌리지 않는다.
  // 기존 데이터가 있는 DB에 컬럼이 추가될 때(ddl-auto: update) 기존 행이 NOT NULL을 위반하지 않도록
  // DB 기본값 0을 명시한다(@ColumnDefault → DDL의 DEFAULT 0). primitive int라 신규 엔티티는 자바에서도 0으로 시작.
  @Column(nullable = false)
  @ColumnDefault("0")
  private int validMessageCount;

  // 이 멤버가 마지막으로 유효 카운트된 시각. 발신자별 디바운스(연속 유효 카운트 최소 간격) 판정에 쓴다.
  @Column
  private LocalDateTime lastValidCountedAt;

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
      LocalDateTime joinedAt
  ) {
    this.chatRoom = chatRoom;
    this.member = member;
    this.joinedAt = joinedAt;
  }

  public static ChatRoomMember join(ChatRoom chatRoom, Member member) {
    return ChatRoomMember.builder()
        .chatRoom(chatRoom)
        .member(member)
        .joinedAt(LocalDateTime.now())
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
   * 이 발신자가 지금({@code now}) 유효 카운트될 수 있는지 디바운스 기준으로 판정한다(이슈 #79). 마지막 유효
   * 카운트 시각으로부터 {@code debounce} 이상 지났거나(=같은 발신자의 연속 유효 카운트 최소 간격 충족), 아직 한
   * 번도 카운트되지 않았으면 가능하다. 타입·길이·교대 조건은 호출 측(정책/방)이 함께 판정한다.
   */
  public boolean isCountEligible(LocalDateTime now, Duration debounce) {
    return this.lastValidCountedAt == null
        || !now.isBefore(this.lastValidCountedAt.plus(debounce));
  }

  /** 유효 메시지 1건을 누적 카운트한다(단조 증가). 마지막 유효 카운트 시각을 {@code now}로 갱신한다. */
  public void countValidMessage(LocalDateTime now) {
    this.validMessageCount++;
    this.lastValidCountedAt = now;
  }
}
