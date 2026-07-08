package com.blursome.blursome.member.domain;

/**
 * 회원 활동 상태. 탈퇴는 행을 삭제하지 않는 소프트삭제({@code WITHDRAWN})로 처리한다.
 *
 * <p>{@code SUSPENDED}는 신고 누적 제재로 인한 임시 정지다(#75). 고유 신고자 5명 임계치 도달 시
 * 자동 전이되며, {@code ACTIVE}가 아니므로 {@link Member#isActive()} 기반 게이트(로그인·채팅·탐색)에서
 * 모두 차단된다. 해제는 운영자 수동(향후 관리자 도구, 후속 이슈)으로 {@code reactivate()}를 호출한다.
 *
 * <p>{@code @Enumerated(EnumType.STRING)}으로 저장하므로 값 이름이 곧 식별자다(순서 무관, append 안전).
 */
public enum ActivityStatus {
  ACTIVE,
  WITHDRAWN,
  SUSPENDED
}
