package com.blursome.blursome.global.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BaseException extends RuntimeException {

  private final HttpStatus httpStatus;
  private final String code;

  private BaseException(String message, HttpStatus httpStatus, String code) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
  }

  public static BaseException from(ErrorCode errorCode) {
    return new BaseException(errorCode.getMessage(), errorCode.getHttpStatus(),
        errorCode.getCode()) {
    };
  }

  public static BaseException from(ErrorCode errorCode, String detailMessage) {
    return new BaseException(detailMessage, errorCode.getHttpStatus(), errorCode.getCode()) {
    };
  }
}
