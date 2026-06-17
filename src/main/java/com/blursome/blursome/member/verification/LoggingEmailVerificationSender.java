package com.blursome.blursome.member.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link EmailVerificationSender}의 임시 로깅 구현(스텁).
 *
 * <p>실제 메일을 보내지 않고 인증 코드를 로그로만 출력한다. 전체 온보딩 플로우(코드 발송 → 검증)를
 * 의존성/SMTP 설정 없이 검증할 수 있도록 1차 기본 구현으로 둔다. 운영 환경에서는 반드시 실제 SMTP
 * 구현({@code SmtpEmailVerificationSender}, TODO)으로 대체해야 한다.
 */
// TODO: spring-boot-starter-mail 기반 SMTP 구현체로 대체하고, 본 스텁은 local 프로파일로 한정한다.
@Slf4j
@Component
public class LoggingEmailVerificationSender implements EmailVerificationSender {

  @Override
  public void send(String email, String code) {
    log.warn("[EMAIL-STUB] 실제 발송 대신 로그로만 출력합니다. to={}, code={}", email, code);
  }
}
