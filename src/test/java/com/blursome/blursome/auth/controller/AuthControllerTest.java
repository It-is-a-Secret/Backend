package com.blursome.blursome.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.auth.cookie.CookieProperties;
import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.oauth.kakao.KakaoOAuthProperties;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.OAuthProvider;
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
  @DisplayName("카카오 로그인 시작 시 카카오 인가 화면으로 302 리다이렉트한다")
  void handleKakaoAuthorize() throws Exception {
    mockMvc.perform(get("/api/auth/oauth/kakao/authorize"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(
            "https://kauth.kakao.com/oauth/authorize"
                + "?response_type=code"
                + "&client_id=test-client-id"
                + "&redirect_uri=http://localhost:8080/api/auth/oauth/kakao/callback"));
  }

  @Test
  @DisplayName("카카오 콜백 시 로그인 후 프론트엔드로 RT 쿠키와 AT 프래그먼트를 담아 302 리다이렉트한다")
  void handleKakaoCallback() throws Exception {
    given(authService.login(eq(OAuthProvider.KAKAO), anyString()))
        .willReturn(new TokenPair("access", "refresh", 1800L, 1209600L));

    mockMvc.perform(get("/api/auth/oauth/kakao/callback").param("code", "abc"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("http://localhost:3000/oauth/success#accessToken=access"))
        .andExpect(cookie().value("refreshToken", "refresh"))
        .andExpect(cookie().httpOnly("refreshToken", true))
        .andExpect(cookie().path("refreshToken", "/api/auth"))
        .andExpect(cookie().maxAge("refreshToken", 1209600));
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

    @Bean
    KakaoOAuthProperties kakaoOAuthProperties() {
      return new KakaoOAuthProperties(
          "test-client-id",
          "test-client-secret",
          "http://localhost:8080/api/auth/oauth/kakao/callback",
          "https://kauth.kakao.com/oauth/authorize",
          "https://kauth.kakao.com/oauth/token",
          "https://kapi.kakao.com/v2/user/me",
          "http://localhost:3000/oauth/success");
    }
  }
}
