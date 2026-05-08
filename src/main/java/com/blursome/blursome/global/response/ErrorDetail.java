package com.blursome.blursome.global.response;

import lombok.Getter;

@Getter
public class ErrorDetail {

  private final String field;
  private final String message;
  private final Object rejectedValue;

  private ErrorDetail(String field, String message, Object rejectedValue) {
    this.field = field;
    this.message = message;
    this.rejectedValue = rejectedValue;
  }

  public static ErrorDetail of(String field, String message, Object rejectedValue) {
    return new ErrorDetail(field, message, rejectedValue);
  }
}
