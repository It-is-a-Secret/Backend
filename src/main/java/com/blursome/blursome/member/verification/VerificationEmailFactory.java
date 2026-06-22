package com.blursome.blursome.member.verification;

import org.springframework.stereotype.Component;

/**
 * 학교 이메일 인증 메일의 제목·본문을 생성하는 콘텐츠 팩토리.
 *
 * <p>전송 수단(SMTP, 외부 메일 API 등)과 무관하게 메일 문구만 책임진다. 전송 어댑터
 * ({@link SmtpEmailVerificationSender} 등)가 이 결과를 받아 실제 발송하므로, 전송 수단을 교체해도
 * 문구 로직은 그대로 재사용된다.
 */
@Component
public class VerificationEmailFactory {

  private static final String SUBJECT = "[BlurSome] 학교 이메일 인증 코드";

  /** 인증 코드를 담은 메일 콘텐츠를 생성한다. 만료 안내는 {@link SchoolEmailVerificationStore#CODE_TTL}을 공유한다. */
  public VerificationEmailContent build(String code) {
    String body = """
        BlurSome 학교 이메일 인증 코드입니다.

        인증 코드: %s

        코드는 발송 시점부터 %d분간 유효합니다.
        본인이 요청하지 않았다면 이 메일을 무시해주세요.
        """.formatted(code, SchoolEmailVerificationStore.CODE_TTL.toMinutes());
    return new VerificationEmailContent(SUBJECT, body);
  }

  /** 전송 수단과 무관한 메일 콘텐츠(제목 + 본문). */
  public record VerificationEmailContent(String subject, String body) {
  }
}
