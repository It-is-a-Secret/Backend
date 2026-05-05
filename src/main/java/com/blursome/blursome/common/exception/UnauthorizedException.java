package com.blursome.blursome.common.exception;

public class UnauthorizedException extends BaseException {

  public UnauthorizedException() {
    super(ErrorCode.UNAUTHORIZED);
  }
}
