package com.blursome.blursome.member.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 단과대학(계열). {@link Department}가 소속 계열을 가리키며, 탐색 점수의 "동일 계열 가산"(+0.5)을
 * 계산하는 기준이 된다(동일 계열 여부 = {@code a.getCollege() == b.getCollege()}).
 *
 * <p>{@code label}은 화면 표시용 한글명이다. enum 특성상 추가·수정은 코드 변경 + 재배포가 필요하다.
 * 선언 순서에 의미가 없으므로 자유롭게 append 한다. (안양대 단과대학 기준)
 */
@Getter
@RequiredArgsConstructor
public enum College {

  THEOLOGY("신학대학"),
  HUMANITIES("인문대학"),
  ARTS("예술대학"),
  SPORTS("스포츠대학"),
  SOCIAL_SCIENCE("사회과학대학"),
  CREATIVE_CONVERGENCE("창의융합대학");

  private final String label;
}
