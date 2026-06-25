package com.blursome.blursome.block.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Member;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 차단 한 건. {@code blocker}가 {@code blocked}를 차단한 단방향 행이며, 노출 차단은 양방향으로 적용한다
 * (탐색·채팅에서 viewer↔상대 어느 방향 차단이든 제외). 차단은 변경 없이 생성·삭제만 한다(해제 시 복구).
 *
 * <p>{@code UNIQUE(blocker_id, blocked_id)}로 중복 차단을, {@code CHECK(blocker_id <> blocked_id)}로
 * 자기 차단을 막는다(앱 레이어 검증 + DB 백스톱).
 */
@Entity
@Getter
@Table(
    name = "block",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_block_pair",
        columnNames = {"blocker_id", "blocked_id"}
    ),
    // 피차단 방향(blocked_id 기준) 조회를 위한 보조 인덱스. blocker 방향은 uk_block_pair가 커버.
    indexes = @Index(name = "idx_block_blocked", columnList = "blocked_id")
)
@Check(name = "chk_block_not_self", constraints = "blocker_id <> blocked_id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Block extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blocker_id", nullable = false)
  private Member blocker;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blocked_id", nullable = false)
  private Member blocked;

  @Builder(access = AccessLevel.PRIVATE)
  private Block(Member blocker, Member blocked) {
    this.blocker = blocker;
    this.blocked = blocked;
  }

  /** 차단 행을 생성한다. 자기 차단 금지는 서비스에서 선검증하며, 여기서도 불변식으로 방어한다. */
  public static Block of(Member blocker, Member blocked) {
    if (blocker.getId().equals(blocked.getId())) {
      throw new IllegalArgumentException("자기 자신은 차단할 수 없습니다.");
    }
    return Block.builder().blocker(blocker).blocked(blocked).build();
  }
}
