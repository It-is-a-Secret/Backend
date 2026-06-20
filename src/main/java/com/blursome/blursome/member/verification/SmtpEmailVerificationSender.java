package com.blursome.blursome.member.verification;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.verification.VerificationEmailFactory.VerificationEmailContent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * {@link EmailVerificationSender}의 Gmail SMTP 전송 어댑터.
 *
 * <p>Spring Boot가 {@code spring.mail.*}로 자동 구성한 {@link JavaMailSender}를 통해 실제 메일을
 * 발송한다. 메일 문구는 {@link VerificationEmailFactory}가, 발신자 표기는 {@link MailProperties}가
 * 책임지며, 본 클래스는 전송만 담당한다. SMTP/JavaMailSender 의존은 이 클래스에만 격리되므로 추후 다른
 * 발송 수단(Gmail REST API, SES 등)으로 교체할 때 이 어댑터만 추가하면 된다.
 *
 * <p>로컬을 포함한 모든 환경에서 기본으로 실제 발송한다. 발송 없이 로그만 남기려면 {@code maillog}
 * 프로파일을 활성화해 {@link LoggingEmailVerificationSender}로 대체할 수 있으며, 그 경우 본 어댑터는
 * 등록되지 않는다({@code !maillog}).
 */
@Slf4j
@Component
@Profile("!maillog")
@RequiredArgsConstructor
public class SmtpEmailVerificationSender implements EmailVerificationSender {

  private final JavaMailSender mailSender;
  private final MailProperties mailProperties;
  private final VerificationEmailFactory emailFactory;

  @Override
  public void send(String email, String code) {
    VerificationEmailContent content = emailFactory.build(code);
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
      helper.setFrom(mailProperties.from());
      helper.setTo(email);
      helper.setSubject(content.subject());
      helper.setText(content.body(), false);
      mailSender.send(message);
    } catch (MessagingException | MailException e) {
      log.error("인증 메일 발송 실패 to={}", email, e);
      throw BaseException.from(MemberErrorCode.MEMBER_EMAIL_SEND_FAILED);
    }
  }
}
