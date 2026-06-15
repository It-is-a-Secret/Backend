package com.blursome.blursome.member.domain;

/**
 * 회원 가입 단계. {@code UNVERIFIED → VERIFIED → COMPLETED} 순으로만 전진한다(단조 증가, 되돌리기 없음).
 *
 * <p>사용자 상태 모델과의 대응:
 * <ul>
 *   <li>{@code UNVERIFIED} — 카카오 로그인 완료 / 학교 인증 미완료 (문서: <b>Unverified</b>)</li>
 *   <li>{@code VERIFIED} — 학교 인증 완료 / 온보딩 미완료 (문서: <b>Verified / Onboarding-Skipped</b>)</li>
 *   <li>{@code COMPLETED} — 학교 인증·온보딩 모두 완료 (문서: <b>Active</b> — 탐색 노출·채팅 가능)</li>
 * </ul>
 * 비로그인(<b>Guest</b>)은 {@code Member} 행이 없는 상태이므로 본 enum 값으로 표현하지 않는다. 문서 'Active'는
 * {@code ActivityStatus.ACTIVE}(탈퇴 여부 축)와 다른 개념이므로 단계 값은 {@code COMPLETED}로 둔다.
 *
 * <p><b>선언 순서가 단계 의미를 가진다(ordinal 비교).</b> 따라서 값 순서를 바꾸거나 중간에 삽입하면 안 되며, 새 단계는 append 만 허용한다.
 */
public enum RegistrationStatus {
  /** 카카오 로그인만 한 상태 / 학교 인증 미완료. */
  UNVERIFIED,
  /** 학교 이메일 인증 완료 / 온보딩 미완료. */
  VERIFIED,
  /** 학교 인증·온보딩 모두 완료. */
  COMPLETED;

  /** 이 단계가 {@code other} 단계 이상인지 비교한다(선언 순서 기준). */
  public boolean isAtLeast(RegistrationStatus other) {
    return this.ordinal() >= other.ordinal();
  }
}
