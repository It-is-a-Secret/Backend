package com.blursome.blursome.member.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@ExtendWith(MockitoExtension.class)
class SmtpEmailVerificationSenderTest {

  private static final String TO = "student@gs.anyang.ac.kr";
  private static final String CODE = "123456";

  @Mock
  private JavaMailSender mailSender;

  private SmtpEmailVerificationSender sender;

  @BeforeEach
  void setUp() {
    sender = new SmtpEmailVerificationSender(
        mailSender, new MailProperties("no-reply@blursome.com"), new VerificationEmailFactory());
  }

  @Test
  @DisplayName("인증 코드 메일을 수신자에게 발송한다")
  void send_dispatchesMail() throws Exception {
    MimeMessage message = new JavaMailSenderImpl().createMimeMessage();
    given(mailSender.createMimeMessage()).willReturn(message);

    sender.send(TO, CODE);

    ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mailSender).send(captor.capture());
    MimeMessage sent = captor.getValue();
    assertThat(sent.getAllRecipients()[0].toString()).isEqualTo(TO);
    assertThat(sent.getSubject()).contains("인증 코드");
    assertThat(sent.getContent().toString()).contains(CODE);
  }

  @Test
  @DisplayName("발송이 실패하면 MEMBER_EMAIL_SEND_FAILED로 변환한다")
  void send_failureMapsToErrorCode() {
    given(mailSender.createMimeMessage()).willReturn(new JavaMailSenderImpl().createMimeMessage());
    willThrow(new MailSendException("smtp down")).given(mailSender).send(any(MimeMessage.class));

    assertThatThrownBy(() -> sender.send(TO, CODE))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(MemberErrorCode.MEMBER_EMAIL_SEND_FAILED.getCode());
  }
}
