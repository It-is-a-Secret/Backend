package com.blursome.blursome.common.exception;

import lombok.Getter;

@Getter
public class BlurSomeException extends RuntimeException {

  private final ErrorCode errorCode;

  public BlurSomeException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
