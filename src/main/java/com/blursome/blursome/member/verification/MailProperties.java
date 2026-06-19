package com.blursome.blursome.member.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 메일 발송 관련 설정.
 *
 * <p>SMTP 접속 정보(host/port/username/password)는 Spring Boot가 {@code spring.mail.*}로 자동 구성하므로,
 * 본 프로퍼티는 애플리케이션 도메인 관점의 발신자 표기({@code from})만 별도로 바인딩한다. 등록은
 * {@code @ConfigurationPropertiesScan}({@code BlursomeApplication})이 담당한다.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String from) {
}
