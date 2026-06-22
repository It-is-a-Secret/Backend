package com.blursome.blursome.auth.controller;

import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.dto.response.AuthTokenResponse;
import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.oauth.kakao.KakaoOAuthProperties;
import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.global.exception.BaseException;
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
   *
   * <p>{@code redirect_uri}는 프론트 오리진별로 다를 수 있어 쿼리 파라미터로 받되, 서버에 등록된
   * 화이트리스트에 포함된 값만 허용한다(오픈 리다이렉트 방지). 생략 시 기본값을 사용한다.
   */
  @Operation(summary = "카카오 로그인 시작", description = "카카오 인가 화면으로 302 리다이렉트한다.")
  @GetMapping("/oauth/kakao/authorize")
  public ResponseEntity<Void> kakaoAuthorize(
      @RequestParam(name = "redirect_uri", required = false) String redirectUri) {
    String resolved = resolveRedirectUri(redirectUri);
    String authorizeUrl = UriComponentsBuilder.fromUriString(kakaoProperties.authorizeUri())
        .queryParam("response_type", "code")
        .queryParam("client_id", kakaoProperties.clientId())
        .queryParam("redirect_uri", resolved)
        .encode(StandardCharsets.UTF_8)
        .toUriString();
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizeUrl)).build();
  }

  /**
   * 카카오 콜백을 처리해 로그인하고 토큰을 응답한다.
   *
   * <p>인가 코드로 토큰을 발급한 뒤, AccessToken은 응답 바디로, RefreshToken은 {@code HttpOnly} 쿠키로
   * 전달한다(재발급 응답과 동일한 형태). 콜백 응답은 팝업 등으로 호출한 클라이언트가 직접 소비한다.
   *
   * <p>토큰 교환 시 카카오는 인가 때와 동일한 {@code redirect_uri}를 요구하므로, 인가 시 사용한 값을
   * 그대로 전달받아 검증 후 사용한다.
   */
  @Operation(summary = "카카오 콜백", description = "인가 코드로 로그인 후 AccessToken(바디)·RefreshToken(쿠키)을 반환한다.")
  @GetMapping("/oauth/kakao/callback")
  public ResponseEntity<DataResponse<AuthTokenResponse>> kakaoCallback(
      @RequestParam String code,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri) {
    String resolved = resolveRedirectUri(redirectUri);
    TokenPair tokens = authService.login(OAuthProvider.KAKAO, code, resolved);
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

  /**
   * 요청된 redirect_uri를 화이트리스트로 검증해 사용할 값을 반환한다.
   *
   * @throws BaseException 등록되지 않은 redirect_uri인 경우(OAUTH_REDIRECT_URI_NOT_ALLOWED)
   */
  private String resolveRedirectUri(String requested) {
    String resolved = kakaoProperties.resolveRedirectUri(requested);
    if (resolved == null) {
      throw BaseException.from(AuthErrorCode.OAUTH_REDIRECT_URI_NOT_ALLOWED);
    }
    return resolved;
  }

  private ResponseEntity<DataResponse<AuthTokenResponse>> tokenResponse(TokenPair tokens) {
    ResponseCookie cookie = cookieFactory.create(tokens.refreshToken(), tokens.refreshTtlSeconds());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(DataResponse.ok(AuthTokenResponse.from(tokens)));
  }
}
