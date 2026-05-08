package com.blursome.blursome.global.exception;

import static com.blursome.blursome.global.exception.code.GlobalErrorCode.INTERNAL_SERVER_ERROR;
import static com.blursome.blursome.global.exception.code.GlobalErrorCode.INVALID_REQUEST_BODY;
import static com.blursome.blursome.global.exception.code.GlobalErrorCode.METHOD_ARGUMENT_NOT_VALID;
import static com.blursome.blursome.global.exception.code.GlobalErrorCode.PARAMETER_NOT_FOUND;
import static com.blursome.blursome.global.exception.code.GlobalErrorCode.RESOURCE_NOT_FOUND;

import com.blursome.blursome.global.exception.code.ErrorCode;
import com.blursome.blursome.global.response.ErrorDetail;
import com.blursome.blursome.global.response.ErrorResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  // 비즈니스 로직에서 명시적으로 발생시킨 도메인 예외를 처리합니다.
  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
    logWarning(e, e.getHttpStatus().value());
    return ResponseEntity
        .status(e.getHttpStatus())
        .body(ErrorResponse.of(e.getHttpStatus(), e.getMessage(), e.getCode()));
  }

  // 필수 요청 파라미터가 누락된 경우를 처리합니다.
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
      MissingServletRequestParameterException e) {
    logWarning(e, PARAMETER_NOT_FOUND.getHttpStatus().value());
    return responseException(PARAMETER_NOT_FOUND);
  }

  // 요청 본문을 읽을 수 없거나 JSON 형식이 올바르지 않은 경우를 처리합니다.
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
      HttpMessageNotReadableException e) {
    logWarning(e, INVALID_REQUEST_BODY.getHttpStatus().value());
    return responseException(INVALID_REQUEST_BODY);
  }

  // @Valid 검증에 실패한 요청 DTO의 필드 오류를 처리합니다.
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentValidation(
      MethodArgumentNotValidException e) {
    List<ErrorDetail> errors = e.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(fieldError -> ErrorDetail.of(
            fieldError.getField(),
            fieldError.getDefaultMessage(),
            fieldError.getRejectedValue()
        ))
        .toList();

    logWarning(e, METHOD_ARGUMENT_NOT_VALID.getHttpStatus().value());
    return responseException(METHOD_ARGUMENT_NOT_VALID, errors);
  }

  // 존재하지 않는 정적 리소스나 매핑되지 않은 요청 경로를 처리합니다.
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
    logWarning(e, RESOURCE_NOT_FOUND.getHttpStatus().value());
    return responseException(RESOURCE_NOT_FOUND);
  }

  // 별도로 처리되지 않은 모든 예외를 서버 내부 오류로 처리합니다.
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    logError(e, INTERNAL_SERVER_ERROR.getHttpStatus().value());
    return responseException(INTERNAL_SERVER_ERROR);
  }

  private ResponseEntity<ErrorResponse> responseException(ErrorCode errorCode) {
    return ResponseEntity
        .status(errorCode.getHttpStatus())
        .body(ErrorResponse.from(errorCode));
  }

  private ResponseEntity<ErrorResponse> responseException(ErrorCode errorCode,
      List<ErrorDetail> reasons) {
    return ResponseEntity
        .status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, reasons));
  }

  private void logWarning(Exception e, int status) {
    log.warn("[{}] {}: {}", status, e.getClass().getSimpleName(), e.getMessage());
  }

  private void logError(Exception e, int status) {
    log.error("[{}] {}: {}", status, e.getClass().getSimpleName(), e.getMessage(), e);
  }
}
