package com.blursome.blursome.common.exception;

public class UserNotFoundException extends BlurSomeException {

  public UserNotFoundException() {
    super(ErrorCode.USER_NOT_FOUND);
  }
}
