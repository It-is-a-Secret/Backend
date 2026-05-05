package com.blursome.blursome.common.exception;

public class UserNotFoundException extends BaseException {

  public UserNotFoundException() {
    super(ErrorCode.USER_NOT_FOUND);
  }
}
