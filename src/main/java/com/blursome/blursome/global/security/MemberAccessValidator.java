package com.blursome.blursome.global.security;

/**
 * 서명·만료가 유효한 Access Token이라도 그 회원이 지금 서비스를 쓸 수 있는지 확인한다(#107).
 *
 * <p>Access Token은 stateless라 탈퇴 직후에도 만료 전까지 유효하다. REST 인증 필터와 STOMP {@code CONNECT}·
 * {@code SUBSCRIBE}가 토큰 검증 뒤 이 검증을 거쳐, 탈퇴한 회원이 남은 토큰으로 보호 기능을 계속 쓰지 못하게 한다. 회원 상태는
 * 회원 도메인이 알고 있으므로 구현은 회원 도메인에 둔다(보안 인프라가 회원 도메인을 직접 참조하지 않게 하는 경계).
 */
public interface MemberAccessValidator {

  /**
   * 회원이 서비스를 쓸 수 있는지 검증한다.
   *
   * @param memberId 토큰에서 복원한 회원 id
   * @throws com.blursome.blursome.global.exception.BaseException 쓸 수 없는 회원인 경우(탈퇴 등)
   */
  void validateAccess(Long memberId);
}
