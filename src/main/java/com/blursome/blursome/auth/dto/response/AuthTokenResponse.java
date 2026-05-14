package com.blursome.blursome.auth.dto.response;

import com.blursome.blursome.auth.dto.TokenPair;

public record AuthTokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn
) {

  private static final String BEARER = "Bearer";

  /** {@code TokenPair}로부터 HTTP 응답용 DTO를 생성한다. */
  public static AuthTokenResponse from(TokenPair tokens) {
    return new AuthTokenResponse(tokens.accessToken(), BEARER, tokens.accessTtlSeconds());
  }
}
