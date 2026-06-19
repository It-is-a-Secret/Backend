package com.blursome.blursome.member.verification;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 6자리 숫자 인증 코드 생성기. 예측 불가능성을 위해 {@link SecureRandom}을 사용한다.
 */
@Component
public class VerificationCodeGenerator {

  private static final int CODE_BOUND = 1_000_000; // 000000 ~ 999999
  private static final String CODE_FORMAT = "%06d";

  private final SecureRandom secureRandom = new SecureRandom();

  /** {@code "000000"} ~ {@code "999999"} 범위의 6자리 코드를 생성한다(앞자리 0 보존). */
  public String generate() {
    return CODE_FORMAT.formatted(secureRandom.nextInt(CODE_BOUND));
  }
}
