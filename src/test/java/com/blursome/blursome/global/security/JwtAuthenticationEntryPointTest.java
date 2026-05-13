package com.blursome.blursome.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.exception.code.JwtErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class JwtAuthenticationEntryPointTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private JwtAuthenticationEntryPoint entryPoint;

  @BeforeEach
  void setUp() {
    entryPoint = new JwtAuthenticationEntryPoint(objectMapper);
  }

  @Test
  @DisplayName("JwtAuthenticationException은 해당 JwtErrorCode로 ErrorResponse를 직렬화한다")
  void commence_whenJwtAuthenticationException_thenWritesErrorResponse() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    JwtAuthenticationException ex = new JwtAuthenticationException(JwtErrorCode.EXPIRED_TOKEN);

    // when
    entryPoint.commence(request, response, ex);

    // then
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.path("code").asText()).isEqualTo(JwtErrorCode.EXPIRED_TOKEN.getCode());
    assertThat(body.path("message").asText()).isEqualTo(JwtErrorCode.EXPIRED_TOKEN.getMessage());
    assertThat(body.path("status").asText()).isEqualTo("Unauthorized");
  }

  @Test
  @DisplayName("일반 AuthenticationException은 기본 UNAUTHORIZED 코드로 응답된다")
  void commence_whenGenericAuthenticationException_thenUsesDefaultCode() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    entryPoint.commence(request, response, new BadCredentialsException("nope"));

    // then
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.path("code").asText()).isEqualTo(JwtErrorCode.UNAUTHORIZED.getCode());
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }
}
