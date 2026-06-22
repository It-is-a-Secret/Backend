package com.blursome.blursome.auth.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

  OAUTH_PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST,
      "지원하지 않는 OAuth 제공자입니다.",
      "AUTH_400_OAUTH_PROVIDER_NOT_SUPPORTED"),
  OAUTH_REDIRECT_URI_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
      "허용되지 않은 redirect_uri입니다.",
      "AUTH_400_OAUTH_REDIRECT_URI_NOT_ALLOWED"),
  OAUTH_TOKEN_EXCHANGE_FAILED(HttpStatus.BAD_GATEWAY,
      "OAuth 토큰 교환에 실패했습니다.",
      "AUTH_502_OAUTH_TOKEN_EXCHANGE_FAILED"),
  OAUTH_USER_INFO_FETCH_FAILED(HttpStatus.BAD_GATEWAY,
      "OAuth 사용자 정보 조회에 실패했습니다.",
      "AUTH_502_OAUTH_USER_INFO_FAILED"),
  REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED,
      "리프레시 토큰이 존재하지 않습니다.",
      "AUTH_401_REFRESH_TOKEN_NOT_FOUND"),
  REFRESH_TOKEN_MISMATCH(HttpStatus.UNAUTHORIZED,
      "리프레시 토큰이 일치하지 않습니다.",
      "AUTH_401_REFRESH_TOKEN_MISMATCH");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
