package com.blursome.blursome.member.verification;

/**
 * 학교 이메일 인증 코드 발송 포트(추상화).
 *
 * <p>발송 메커니즘(SMTP, 외부 메일 API 등)을 도메인/서비스와 분리한다. 기본값은 로컬을 포함한 모든
 * 환경에서 Gmail SMTP로 실제 발송하는 {@link SmtpEmailVerificationSender}이며, {@code maillog} 프로파일을
 * 활성화하면 코드를 로그로만 남기는 {@link LoggingEmailVerificationSender}로 대체된다. 새 발송 수단(외부
 * 메일 API 등)은 이 포트의 구현체를 추가해 확장한다.
 */
public interface EmailVerificationSender {

  /**
   * 대상 이메일로 인증 코드를 발송한다.
   *
   * @param email 인증 코드를 받을 학교 이메일 주소
   * @param code 6자리 인증 코드
   * @throws com.blursome.blursome.global.exception.BaseException 발송에 실패한 경우(구현체에 따라)
   */
  void send(String email, String code);
}
