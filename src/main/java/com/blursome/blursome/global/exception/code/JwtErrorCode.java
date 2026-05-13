package com.blursome.blursome.global.exception.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum JwtErrorCode implements ErrorCode {
  // 401 Unauthorized: 인증 실패
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 JWT 입니다.", "JWT_401_INVALID"),
  EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 JWT 입니다.", "JWT_401_EXPIRED"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증 정보가 필요합니다.", "JWT_401_UNAUTHORIZED");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
