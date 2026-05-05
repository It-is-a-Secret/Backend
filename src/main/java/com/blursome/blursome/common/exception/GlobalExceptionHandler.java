package com.blursome.blursome.common.exception;

import com.blursome.blursome.common.response.ApiResponse;
import com.blursome.blursome.common.response.ErrorDetail;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static com.blursome.blursome.common.exception.ErrorCode.INTERNAL_SERVER_ERROR;
import static com.blursome.blursome.common.exception.ErrorCode.INVALID_REQUEST_BODY;
import static com.blursome.blursome.common.exception.ErrorCode.METHOD_ARGUMENT_NOT_VALID;
import static com.blursome.blursome.common.exception.ErrorCode.PARAMETER_NOT_FOUND;
import static com.blursome.blursome.common.exception.ErrorCode.RESOURCE_NOT_FOUND;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException e) {
    ErrorCode errorCode = e.getErrorCode();
    logWarning(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
      MissingServletRequestParameterException e) {
    ErrorCode errorCode = PARAMETER_NOT_FOUND;
    logWarning(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
      HttpMessageNotReadableException e) {
    ErrorCode errorCode = INVALID_REQUEST_BODY;
    logWarning(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<List<ErrorDetail>>> handleMethodArgumentValidation(
      MethodArgumentNotValidException e) {
    ErrorCode errorCode = METHOD_ARGUMENT_NOT_VALID;
    List<ErrorDetail> errors = e.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(fieldError -> ErrorDetail.of(
            fieldError.getField(),
            fieldError.getDefaultMessage(),
            fieldError.getRejectedValue()
        ))
        .toList();

    logWarning(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage(), errors);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
    ErrorCode errorCode = RESOURCE_NOT_FOUND;
    logWarning(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
    ErrorCode errorCode = INTERNAL_SERVER_ERROR;
    logError(e, errorCode.getHttpStatus().value());
    return responseException(errorCode.getHttpStatus(), errorCode.getMessage());
  }

  private ResponseEntity<ApiResponse<Void>> responseException(HttpStatus status, String message) {
    ApiResponse<Void> response = ApiResponse.response(status, message);

    return ResponseEntity
        .status(status)
        .body(response);
  }

  private <T> ResponseEntity<ApiResponse<T>> responseException(
      HttpStatus status,
      String message,
      T data
  ) {
    ApiResponse<T> response = ApiResponse.response(status, message, data);

    return ResponseEntity
        .status(status)
        .body(response);
  }

  private void logWarning(Exception e, int status) {
    log.warn("[{}] {}: {}", status, e.getClass().getSimpleName(), e.getMessage());
  }

  private void logError(Exception e, int status) {
    log.error("[{}] {}: {}", status, e.getClass().getSimpleName(), e.getMessage(), e);
  }
}
