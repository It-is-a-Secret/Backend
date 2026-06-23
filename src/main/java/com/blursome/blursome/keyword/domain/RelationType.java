package com.blursome.blursome.keyword.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 태그 간 관계 유형. {@code @Enumerated(EnumType.STRING)}으로 저장한다.
 *
 * <p>각 유형은 키워드 점수(K) 계산 시 부여되는 기본 점수({@link #getScore()})를 가진다(검토 개선안 #6 확정 점수표).
 * <b>동일(+10)</b>은 별도 관계 행이 아니라 두 회원의 {@code tag_id} 일치로 판정하므로 여기 포함하지 않으며,
 * 다양성 보너스(+5) 등 합산·정규화는 탐색 알고리즘(Phase 2)에서 처리한다.
 *
 * <p>선언 순서나 이름을 바꾸면 저장값과 어긋나므로 변경하지 말고, 새 유형은 append만 허용한다.
 */
@Getter
@RequiredArgsConstructor
public enum RelationType {

  /** 유사 키워드 조합(같은 카테고리 내 비슷한 취향). */
  SIMILAR(5),
  /** 보완형 키워드 조합(서로 다르지만 관계 형성에 긍정적). */
  COMPLEMENT(3),
  /** 충돌 가능 키워드 조합(생활·연락 패턴 등 충돌 가능성). */
  CONFLICT(-4);

  /** 키워드 점수 계산 시 이 관계가 기여하는 기본 점수. */
  private final int score;
}
