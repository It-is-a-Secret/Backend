package com.blursome.blursome.global.response;

import com.blursome.blursome.global.exception.code.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse extends BaseResponse {

  private final String message;
  private final String code; // 도메인 별 세부 오류 코드를 위한 필드
  private final List<ErrorDetail> reasons;

  public ErrorResponse(
      HttpStatus status,
      String message,
      String code,
      List<ErrorDetail> reasons
  ) {
    super(status);
    this.message = message;
    this.code = code;
    this.reasons = reasons;
  }


  public static ErrorResponse from(ErrorCode errorCode) {
    HttpStatus status = errorCode.getHttpStatus();
    String message = errorCode.getMessage();
    String code = errorCode.getCode();

    return new ErrorResponse(status, message, code, null);
  }


  public static ErrorResponse of(ErrorCode errorCode, List<ErrorDetail> reasons) {
    HttpStatus status = errorCode.getHttpStatus();
    String message = errorCode.getMessage();
    String code = errorCode.getCode();

    return new ErrorResponse(status, message, code, reasons);
  }

  public static ErrorResponse of(HttpStatus status, String message, String code) {

    return new ErrorResponse(status, message, code, null);
  }

}