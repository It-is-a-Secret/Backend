package com.blursome.blursome.report.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {

  REPORT_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
      "자기 자신은 신고할 수 없습니다.",
      "REPORT_400_SELF_NOT_ALLOWED"
  ),
  REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT,
      "이미 신고한 대상입니다.",
      "REPORT_409_ALREADY_EXISTS"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
