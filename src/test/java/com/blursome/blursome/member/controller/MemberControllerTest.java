package com.blursome.blursome.member.controller;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.auth.cookie.CookieProperties;
import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.service.MemberOnboardingService;
import com.blursome.blursome.member.service.MemberProfileService;
import com.blursome.blursome.member.service.MemberWithdrawalService;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = MemberController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(MemberControllerTest.TestBeans.class)
class MemberControllerTest {

  private static final long MEMBER_ID = 100L;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MemberWithdrawalService memberWithdrawalService;

  @MockitoBean
  @SuppressWarnings("unused")
  private MemberOnboardingService memberOnboardingService;

  @MockitoBean
  @SuppressWarnings("unused")
  private MemberProfileService memberProfileService;

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

  @Test
  @DisplayName("회원 탈퇴 시 204와 함께 Refresh Token 쿠키를 만료시키고 본문이 없다(#107)")
  void handleWithdraw() throws Exception {
    mockMvc.perform(delete("/api/members/me"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.allOf(
            Matchers.containsString("refreshToken="),
            Matchers.containsString("Max-Age=0"),
            Matchers.containsString("Path=/api/auth"),
            Matchers.containsString("HttpOnly"))));

    verify(memberWithdrawalService).withdraw(MEMBER_ID);
  }

  @Test
  @DisplayName("비활성(탈퇴·정지) 회원의 탈퇴 요청은 403 MEMBER_403_INACTIVE이고 쿠키를 건드리지 않는다(#107)")
  void handleWithdrawWhenInactive() throws Exception {
    willThrow(BaseException.from(MemberErrorCode.MEMBER_INACTIVE))
        .given(memberWithdrawalService).withdraw(MEMBER_ID);

    mockMvc.perform(delete("/api/members/me"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MEMBER_403_INACTIVE"))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @TestConfiguration
  static class TestBeans implements WebMvcConfigurer {

    @Bean
    CookieProperties cookieProperties() {
      return new CookieProperties(false, "Lax");
    }

    @Bean
    RefreshTokenCookieFactory refreshTokenCookieFactory(CookieProperties props) {
      return new RefreshTokenCookieFactory(props);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
  }
}
