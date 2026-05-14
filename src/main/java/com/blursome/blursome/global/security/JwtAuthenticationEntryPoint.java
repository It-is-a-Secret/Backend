package com.blursome.blursome.global.security;

import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.exception.code.JwtErrorCode;
import com.blursome.blursome.global.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException
  ) throws IOException {
    JwtErrorCode errorCode = resolveErrorCode(authException);
    log.warn("JWT 인증 실패: code={}, message={}", errorCode.getCode(), authException.getMessage());

    response.setStatus(errorCode.getHttpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());

    ErrorResponse body = ErrorResponse.from(errorCode);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }

  private JwtErrorCode resolveErrorCode(AuthenticationException authException) {
    if (authException instanceof JwtAuthenticationException jwtException) {
      return jwtException.getErrorCode();
    }
    return JwtErrorCode.UNAUTHORIZED;
  }
}
