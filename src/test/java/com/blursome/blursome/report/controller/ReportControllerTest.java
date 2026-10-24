package com.blursome.blursome.report.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.report.domain.ReportReason;
import com.blursome.blursome.report.service.ReportService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 신고 요청의 사유 코드 역직렬화와 잘못된 코드의 400 응답을 검증한다(#102). */
@WebMvcTest(
    controllers = ReportController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ReportControllerTest.PrincipalResolverConfig.class)
class ReportControllerTest {

  private static final long MEMBER_ID = 100L;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ReportService reportService;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(JwtAuthentication.of(MEMBER_ID, MemberRole.USER));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"INAPPROPRIATE_CHAT", "SEXUAL_HARASSMENT", "COERCIVE_MEETING",
      "DANGEROUS_PERSON", "FINANCIAL_OR_PERSONAL_INFO_REQUEST", "SPAM", "ETC"})
  @DisplayName("화면 7개 항목의 사유 코드는 그대로 역직렬화되어 서비스로 전달된다")
  void report_selectableReason_isDeserialized(String reason) throws Exception {
    mockMvc.perform(post("/api/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(reason)))
        .andExpect(status().isNoContent());

    verify(reportService).report(eq(MEMBER_ID),
        argThat(req -> req.reason() == ReportReason.valueOf(reason)));
  }

  @Test
  @DisplayName("정의되지 않은 사유 코드는 400(INVALID_REQUEST_BODY)으로 거절된다")
  void report_unknownReason_returns400() throws Exception {
    mockMvc.perform(post("/api/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("SEXUAL_CONTENT")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_INVALID_REQUEST_BODY"));

    verify(reportService, never()).report(any(), any());
  }

  @Test
  @DisplayName("사유가 없으면 400(METHOD_ARGUMENT_NOT_VALID)으로 거절된다")
  void report_missingReason_returns400() throws Exception {
    mockMvc.perform(post("/api/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetMemberId\": 200}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID"));

    verify(reportService, never()).report(any(), any());
  }

  private String body(String reason) {
    return "{\"targetMemberId\": 200, \"chatRoomId\": 10, \"reason\": \"" + reason + "\"}";
  }

  @TestConfiguration
  static class PrincipalResolverConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
  }
}
