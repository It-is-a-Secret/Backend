package com.blursome.blursome.global.exception;

import com.blursome.blursome.global.exception.code.JwtErrorCode;
import org.springframework.security.core.AuthenticationException;

public class JwtAuthenticationException extends AuthenticationException {

  private final JwtErrorCode errorCode;

  public JwtAuthenticationException(JwtErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }


  public JwtErrorCode getErrorCode() {
    return errorCode;
  }
}
