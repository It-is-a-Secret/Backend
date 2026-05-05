package com.blursome.blursome.common.exception;

public class UnauthorizedException extends BlurSomeException {

  public UnauthorizedException() {
    super(ErrorCode.UNAUTHORIZED);
  }
}
