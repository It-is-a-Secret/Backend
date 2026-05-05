package com.blursome.blursome.common.exception;

public class NotificationNotFoundException extends BaseException {

  public NotificationNotFoundException() {
    super(ErrorCode.NOTIFICATION_NOT_FOUND);
  }
}
