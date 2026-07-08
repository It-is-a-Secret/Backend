package com.blursome.blursome.report.domain;

import com.blursome.blursome.chat.domain.ChatRoom;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 신고 한 건. {@code reporter}가 {@code target}을 신고한다. {@code chatRoom}이 있으면 채팅방 신고,
 * {@code null}이면 프로필 신고다. 임계치는 대상별 <b>고유 신고자 수</b>({@code COUNT(DISTINCT reporter)})로
 * 판정하므로(마스터 §6-17), 동일 (reporter, target, chatRoom) 중복 신고는 {@code UNIQUE}로 막는다.
 *
 * <p>{@code CHECK(reporter_id <> target_id)}로 자기 신고를 막는다(앱 검증 + DB 백스톱). MySQL에서 NULL은
 * 유니크상 서로 구별되므로, 프로필 신고(chat_room_id NULL)는 유니크로 중복이 막히지 않으나 고유 신고자 집계가
 * 이를 흡수한다.
 */
@Entity
@Getter
@Table(
    name = "report",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_report_reporter_target_room",
        columnNames = {"reporter_id", "target_id", "chat_room_id"}
    ),
    // 임계치 집계(대상 + 최근 N일)를 위한 보조 인덱스.
    indexes = @Index(name = "idx_report_target_created", columnList = "target_id, created_at")
)
@Check(name = "chk_report_not_self", constraints = "reporter_id <> target_id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reporter_id", nullable = false)
  private Member reporter;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "target_id", nullable = false)
  private Member target;

  // 채팅방 신고면 해당 방, 프로필 신고면 null.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "chat_room_id")
  private ChatRoom chatRoom;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ReportReason reason;

  @Column(length = 500)
  private String detail;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReportStatus status;

  @Builder(access = AccessLevel.PRIVATE)
  private Report(Member reporter, Member target, ChatRoom chatRoom,
      ReportReason reason, String detail, ReportStatus status) {
    this.reporter = reporter;
    this.target = target;
    this.chatRoom = chatRoom;
    this.reason = reason;
    this.detail = detail;
    this.status = status;
  }

  /** 신고 행을 생성한다(상태 {@code RECEIVED}). 자기 신고 금지는 서비스에서 선검증하며 여기서도 방어한다. */
  public static Report create(Member reporter, Member target, ChatRoom chatRoom,
      ReportReason reason, String detail) {
    if (reporter.getId().equals(target.getId())) {
      throw new IllegalArgumentException("자기 자신은 신고할 수 없습니다.");
    }
    return Report.builder()
        .reporter(reporter)
        .target(target)
        .chatRoom(chatRoom)
        .reason(reason)
        .detail(detail)
        .status(ReportStatus.RECEIVED)
        .build();
  }
}
