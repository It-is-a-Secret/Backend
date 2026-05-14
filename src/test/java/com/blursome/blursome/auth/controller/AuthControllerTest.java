package com.blursome.blursome.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.auth.cookie.CookieProperties;
import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = AuthController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerTest.TestBeans.class)
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuthService authService;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtTokenProvider jwtTokenProvider;

  @Test
  @DisplayName("카카오 로그인 성공 시 200과 RefreshToken 쿠키를 반환한다")
  void handleKakaoLoginSuccess() throws Exception {
    given(authService.loginWithKakao(anyString()))
        .willReturn(new TokenPair("access", "refresh", 1800L, 1209600L));

    mockMvc.perform(post("/api/auth/oauth/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"abc\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").value("access"))
        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(cookie().value("refreshToken", "refresh"))
        .andExpect(cookie().httpOnly("refreshToken", true))
        .andExpect(cookie().path("refreshToken", "/api/auth"))
        .andExpect(cookie().maxAge("refreshToken", 1209600));
  }

  @Test
  @DisplayName("code 누락 시 400과 METHOD_ARGUMENT_NOT_VALID를 반환한다")
  void handleKakaoLoginInvalidRequest() throws Exception {
    mockMvc.perform(post("/api/auth/oauth/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID"));
  }

  @Test
  @DisplayName("RefreshToken 쿠키가 없으면 401과 REFRESH_TOKEN_NOT_FOUND를 반환한다")
  void handleRefreshWithoutCookie() throws Exception {
    given(authService.refresh(null))
        .willThrow(BaseException.from(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

    mockMvc.perform(post("/api/auth/token/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_401_REFRESH_TOKEN_NOT_FOUND"));
  }

  @Test
  @DisplayName("RefreshToken 쿠키가 있으면 새 토큰과 갱신된 쿠키를 반환한다")
  void handleRefreshWithCookie() throws Exception {
    given(authService.refresh("refresh-old"))
        .willReturn(new TokenPair("access-new", "refresh-new", 1800L, 1209600L));

    mockMvc.perform(post("/api/auth/token/refresh")
            .cookie(new Cookie("refreshToken", "refresh-old")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").value("access-new"))
        .andExpect(cookie().value("refreshToken", "refresh-new"));
  }

  @Test
  @DisplayName("RefreshToken 쿠키와 함께 로그아웃 시 서비스에 토큰을 전달하고 쿠키를 만료시킨다")
  void handleLogoutWithCookie() throws Exception {
    mockMvc.perform(post("/api/auth/logout")
            .cookie(new Cookie("refreshToken", "refresh-old")))
        .andExpect(status().isNoContent())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(cookie().maxAge("refreshToken", 0));

    verify(authService).logout("refresh-old");
  }

  @Test
  @DisplayName("RefreshToken 쿠키가 없어도 204를 반환하고 쿠키 만료 헤더를 내려준다")
  void handleLogoutWithoutCookie() throws Exception {
    mockMvc.perform(post("/api/auth/logout"))
        .andExpect(status().isNoContent())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(cookie().maxAge("refreshToken", 0));

    verify(authService).logout(null);
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    CookieProperties cookieProperties() {
      return new CookieProperties(false, "Lax");
    }

    @Bean
    RefreshTokenCookieFactory refreshTokenCookieFactory(CookieProperties props) {
      return new RefreshTokenCookieFactory(props);
    }
  }
}
