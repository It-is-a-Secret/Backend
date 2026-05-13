package com.blursome.blursome.auth.controller;

import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.dto.request.KakaoLoginRequest;
import com.blursome.blursome.auth.dto.response.AuthTokenResponse;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.response.DataResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

  @PostMapping("/oauth/kakao")
  public ResponseEntity<DataResponse<AuthTokenResponse>> kakaoLogin(
      @Valid @RequestBody KakaoLoginRequest request
  ) {
    TokenPair tokens = authService.loginWithKakao(request.code());
    return tokenResponse(tokens);
  }

  @PostMapping("/token/refresh")
  public ResponseEntity<DataResponse<AuthTokenResponse>> refresh(
      @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false)
      String refreshToken
  ) {
    TokenPair tokens = authService.refresh(refreshToken);
    return tokenResponse(tokens);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@AuthenticationPrincipal Long memberId) {
    authService.logout(memberId);
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
