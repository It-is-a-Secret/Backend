package com.blursome.blursome.global.exception.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum JwtErrorCode implements ErrorCode {
  // 401 Unauthorized: 인증 실패
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 JWT 입니다.", "JWT_401_INVALID");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
