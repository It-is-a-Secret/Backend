package com.blursome.blursome.notification.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PushErrorCode implements ErrorCode {

  PUSH_ENDPOINT_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
      "지원하지 않는 푸시 서비스 주소입니다.",
      "PUSH_400_ENDPOINT_NOT_ALLOWED"
  ),
  PUSH_INVALID_KEYS(HttpStatus.BAD_REQUEST,
      "구독 키 형식이 올바르지 않습니다.",
      "PUSH_400_INVALID_KEYS"
  ),
  PUSH_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE,
      "푸시 알림이 설정되지 않았습니다.",
      "PUSH_503_NOT_CONFIGURED"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
