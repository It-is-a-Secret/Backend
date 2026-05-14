package com.blursome.blursome.auth.controller;

import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.dto.request.KakaoLoginRequest;
import com.blursome.blursome.auth.dto.response.AuthTokenResponse;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.member.domain.OAuthProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final RefreshTokenCookieFactory cookieFactory;

  /** 카카오 OAuth 인가 코드로 로그인하고 토큰 쌍을 발급한다. */
  @PostMapping("/oauth/kakao")
  public ResponseEntity<DataResponse<AuthTokenResponse>> kakaoLogin(
      @Valid @RequestBody KakaoLoginRequest request
  ) {
    TokenPair tokens = authService.login(OAuthProvider.KAKAO, request.code());
    return tokenResponse(tokens);
  }

  /** 쿠키의 RefreshToken으로 새 토큰 쌍을 회전 발급한다. */
  @PostMapping("/token/refresh")
  public ResponseEntity<DataResponse<AuthTokenResponse>> refresh(
      @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false)
      String refreshToken
  ) {
    TokenPair tokens = authService.refresh(refreshToken);
    return tokenResponse(tokens);
  }

  /** RT 쿠키 기반 로그아웃(멱등). 부재·만료·위조 RT여도 204를 반환하고 쿠키를 만료시킨다. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false)
      String refreshToken
  ) {
    authService.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieFactory.delete().toString())
        .build();
  }

  private ResponseEntity<DataResponse<AuthTokenResponse>> tokenResponse(TokenPair tokens) {
    ResponseCookie cookie = cookieFactory.create(tokens.refreshToken(), tokens.refreshTtlSeconds());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(DataResponse.ok(AuthTokenResponse.from(tokens)));
  }
}
