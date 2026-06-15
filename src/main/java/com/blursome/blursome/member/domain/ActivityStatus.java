package com.blursome.blursome.member.domain;

/**
 * 회원 활동 상태. 탈퇴는 행을 삭제하지 않는 소프트삭제({@code WITHDRAWN})로 처리한다.
 *
 * <p>{@code SUSPENDED}(관리자 정지)는 후순위 — 필요 시 값을 append 한다.
 */
public enum ActivityStatus {
  ACTIVE,
  WITHDRAWN
  // SUSPENDED
}
