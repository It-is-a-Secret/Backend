package com.blursome.blursome.global.exception.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {

  // 400 Bad Request
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "유효하지 않은 요청입니다.", "CLIENT_ERROR_400_INVALID_REQUEST"),
  PARAMETER_NOT_FOUND(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다.", "CLIENT_ERROR_400_PARAMETER_NOT_FOUND"),
  INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다.", "CLIENT_ERROR_400_INVALID_REQUEST_BODY"),
  METHOD_ARGUMENT_NOT_VALID(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.", "CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID"),

  // 404 Not Found
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.", "CLIENT_ERROR_404_RESOURCE_NOT_FOUND"),

  // 500 Internal Server Error
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.", "SERVER_ERROR_500_INTERNAL_SERVER_ERROR");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
