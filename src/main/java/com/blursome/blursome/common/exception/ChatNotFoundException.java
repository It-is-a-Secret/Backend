package com.blursome.blursome.common.exception;

public class ChatNotFoundException extends BlurSomeException {

  public ChatNotFoundException() {
    super(ErrorCode.CHAT_NOT_FOUND);
  }
}
