package com.blursome.blursome.member.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link EmailVerificationSender}의 로깅 구현(스텁, opt-in).
 *
 * <p>실제 메일을 보내지 않고 인증 코드를 로그로만 출력한다. 기본값은 모든 환경에서 실제 발송
 * ({@link SmtpEmailVerificationSender})이며, SMTP 설정·계정 없이 온보딩 플로우(코드 발송 → 검증)만
 * 점검하고 싶을 때 {@code maillog} 프로파일을 활성화하면 본 스텁으로 대체된다.
 */
@Slf4j
@Component
@Profile("maillog")
public class LoggingEmailVerificationSender implements EmailVerificationSender {

  @Override
  public void send(String email, String code) {
    log.warn("[EMAIL-STUB] 실제 발송 대신 로그로만 출력합니다. to={}, code={}", email, code);
  }
}
