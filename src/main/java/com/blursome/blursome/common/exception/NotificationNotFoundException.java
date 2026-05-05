package com.blursome.blursome.common.exception;

public class NotificationNotFoundException extends BlurSomeException {

  public NotificationNotFoundException() {
    super(ErrorCode.NOTIFICATION_NOT_FOUND);
  }
}
