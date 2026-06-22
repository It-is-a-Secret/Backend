package com.blursome.blursome.member.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.member.verification.VerificationEmailFactory.VerificationEmailContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerificationEmailFactoryTest {

  private final VerificationEmailFactory factory = new VerificationEmailFactory();

  @Test
  @DisplayName("제목·본문에 인증 코드와 만료 시간 안내가 포함된다")
  void build_includesCodeAndExpiry() {
    VerificationEmailContent content = factory.build("123456");

    assertThat(content.subject()).contains("인증 코드");
    assertThat(content.body()).contains("123456");
    assertThat(content.body()).contains("5분");
  }
}
