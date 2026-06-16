package com.blursome.blursome.auth.controller;

import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.dto.response.AuthTokenResponse;
import com.blursome.blursome.auth.oauth.kakao.KakaoOAuthProperties;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.member.domain.OAuthProvider;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final RefreshTokenCookieFactory cookieFactory;
  private final KakaoOAuthProperties kakaoProperties;

  /**
   * 카카오 인가 화면으로 리다이렉트한다.
   *
   * <p>클라이언트는 이 엔드포인트로 진입만 하면 되고, 백엔드가 {@code client_id}·{@code redirect_uri}로
   * 인가 URL을 구성해 302로 보낸다. 카카오 로그인·동의 후 {@code redirect_uri}(콜백)로 인가 코드가 전달된다.
   */
  @Operation(summary = "카카오 로그인 시작", description = "카카오 인가 화면으로 302 리다이렉트한다.")
  @GetMapping("/oauth/kakao/authorize")
  public ResponseEntity<Void> kakaoAuthorize() {
    String authorizeUrl = UriComponentsBuilder.fromUriString(kakaoProperties.authorizeUri())
        .queryParam("response_type", "code")
        .queryParam("client_id", kakaoProperties.clientId())
        .queryParam("redirect_uri", kakaoProperties.redirectUri())
        .encode(StandardCharsets.UTF_8)
        .toUriString();
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizeUrl)).build();
  }

  /**
   * 카카오 콜백을 처리해 로그인하고 프론트엔드로 리다이렉트한다.
   *
   * <p>인가 코드로 토큰을 발급한 뒤, RefreshToken은 {@code HttpOnly} 쿠키로 심고 프론트엔드 성공 URL로
   * 302 리다이렉트하며 AccessToken은 URL 프래그먼트({@code #accessToken=...})로 전달한다.
   */
  @Operation(summary = "카카오 콜백", description = "인가 코드로 로그인 후 프론트엔드로 리다이렉트한다(RT 쿠키 + AT 프래그먼트).")
  @GetMapping("/oauth/kakao/callback")
  public ResponseEntity<Void> kakaoCallback(@RequestParam String code) {
    TokenPair tokens = authService.login(OAuthProvider.KAKAO, code);
    ResponseCookie cookie = cookieFactory.create(tokens.refreshToken(), tokens.refreshTtlSeconds());

    String redirectUrl = UriComponentsBuilder.fromUriString(kakaoProperties.successRedirectUri())
        .fragment("accessToken=" + tokens.accessToken())
        .encode(StandardCharsets.UTF_8)
        .toUriString();
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .location(URI.create(redirectUrl))
        .build();
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
