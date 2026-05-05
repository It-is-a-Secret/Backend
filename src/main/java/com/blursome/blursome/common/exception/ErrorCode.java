package com.blursome.blursome.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  // User
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),

  // Chat
  CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다."),

  // Notification
  NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

  // Auth
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증되지 않은 요청입니다."),

  // Common
  PARAMETER_NOT_FOUND(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
  INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다."),
  METHOD_ARGUMENT_NOT_VALID(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus httpStatus;
  private final String message;
}
