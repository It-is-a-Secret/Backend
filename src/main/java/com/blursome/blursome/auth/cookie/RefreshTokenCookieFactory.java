package com.blursome.blursome.auth.cookie;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenCookieFactory {

  public static final String COOKIE_NAME = "refreshToken";
  private static final String COOKIE_PATH = "/api/auth";

  private final CookieProperties cookieProperties;

  /** 리프레시 토큰 값과 만료 시간으로 쿠키를 생성한다. */
  public ResponseCookie create(String refreshToken, long maxAgeSeconds) {
    return baseBuilder(refreshToken, maxAgeSeconds).build();
  }

  /** 리프레시 토큰 쿠키를 만료(max-age=0)시키는 쿠키를 반환한다. */
  public ResponseCookie delete() {
    return baseBuilder("", 0).build();
  }

  private ResponseCookie.ResponseCookieBuilder baseBuilder(String value, long maxAgeSeconds) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(cookieProperties.secure())
        .sameSite(cookieProperties.sameSite())
        .path(COOKIE_PATH)
        .maxAge(maxAgeSeconds);
  }
}
