package com.blursome.blursome.member.verification;

/**
 * 학교 이메일 인증 코드 발송 포트(추상화).
 *
 * <p>발송 메커니즘(SMTP, 외부 메일 API 등)을 도메인/서비스와 분리한다. 1차는 로깅 스텁
 * ({@link LoggingEmailVerificationSender})만 제공하고, 실제 SMTP 구현은 후속 작업으로 추가한다.
 */
public interface EmailVerificationSender {

  /**
   * 대상 이메일로 인증 코드를 발송한다.
   *
   * @param email 인증 코드를 받을 학교 이메일 주소
   * @param code 6자리 인증 코드
   */
  void send(String email, String code);
}
