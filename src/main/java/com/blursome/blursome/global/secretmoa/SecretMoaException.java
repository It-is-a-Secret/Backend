package com.blursome.blursome.global.secretmoa;

import java.util.regex.Pattern;

/**
 * SecretMoa 호출 실패. 메시지에는 HTTP 상태, SecretMoa 오류 코드, {@code X-Request-Id}만 담는다.
 *
 * <p>응답 본문, 요청 헤더(API Key), HTTP 클라이언트의 원인 예외는 넣지 않는다. 원인 예외의 메시지에는
 * 응답 본문이나 파싱 중이던 JSON 일부가 들어갈 수 있기 때문이다. 오류 코드와 요청 ID는 형식 검사를
 * 통과한 값만 남긴다.
 */
public class SecretMoaException extends RuntimeException {

  /** 실패 종류. 재시도 여부 판단에 쓴다. */
  public enum Reason {
    /** 401: API Key가 없거나 틀림, 폐기됨. */
    UNAUTHORIZED(false),
    /** 403: Application에 해당 Role 권한이 없거나 비활성. */
    FORBIDDEN(false),
    /** 404: Database·Target·Role·Lease가 없음. */
    NOT_FOUND(false),
    /** 409: 회수 불가(S3) 등 현재 상태와 충돌. */
    CONFLICT(false),
    /** 429: SecretMoa 대상 작업이 바쁨. */
    BUSY(true),
    /** 503 등 5xx: 대상 DB·AWS 일시 장애. */
    UNAVAILABLE(true),
    /** 연결 실패·타임아웃. */
    UNREACHABLE(true),
    /** 2xx지만 응답을 해석할 수 없음. */
    INVALID_RESPONSE(false),
    /** 그 밖의 4xx (예: 400 잘못된 요청). */
    UNEXPECTED_STATUS(false);

    private final boolean retryable;

    Reason(boolean retryable) {
      this.retryable = retryable;
    }
  }

  private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,40}");
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private final Reason reason;
  private final int status;
  private final String errorCode;
  private final String requestId;

  private SecretMoaException(Reason reason, int status, String errorCode, String requestId, String detail) {
    super(message(reason, status, errorCode, requestId, detail));
    this.reason = reason;
    this.status = status;
    this.errorCode = errorCode;
    this.requestId = requestId;
  }

  /** HTTP 오류 응답으로 예외를 만든다. 형식에 맞지 않는 오류 코드·요청 ID는 버린다. */
  static SecretMoaException fromStatus(int status, String errorCode, String requestId) {
    return new SecretMoaException(reasonOf(status), status, sanitize(errorCode, ERROR_CODE),
        sanitize(requestId, REQUEST_ID), null);
  }

  /** 연결 실패·타임아웃. 원인 예외 대신 예외 클래스 이름만 남긴다. */
  static SecretMoaException unreachable(String exceptionType) {
    return new SecretMoaException(Reason.UNREACHABLE, 0, null, null, exceptionType);
  }

  /** 2xx 응답을 해석하지 못함. 파싱 예외 메시지에 응답 일부가 있을 수 있어 원인을 붙이지 않는다. */
  static SecretMoaException invalidResponse(String detail) {
    return new SecretMoaException(Reason.INVALID_RESPONSE, 0, null, null, detail);
  }

  static Reason reasonOf(int status) {
    return switch (status) {
      case 401 -> Reason.UNAUTHORIZED;
      case 403 -> Reason.FORBIDDEN;
      case 404 -> Reason.NOT_FOUND;
      case 409 -> Reason.CONFLICT;
      case 429 -> Reason.BUSY;
      default -> status >= 500 ? Reason.UNAVAILABLE : Reason.UNEXPECTED_STATUS;
    };
  }

  public Reason reason() {
    return reason;
  }

  /** HTTP 상태. 응답을 받지 못했으면 0. */
  public int status() {
    return status;
  }

  /** SecretMoa 오류 코드 (예: {@code issuance_unavailable}). 없으면 null. */
  public String errorCode() {
    return errorCode;
  }

  /** SecretMoa {@code X-Request-Id}. 감사 로그와 대조할 때 쓴다. 없으면 null. */
  public String requestId() {
    return requestId;
  }

  /** 잠시 뒤 다시 시도하면 성공할 수 있는 실패면 true. */
  public boolean isRetryable() {
    return reason.retryable;
  }

  private static String sanitize(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches() ? value : null;
  }

  private static String message(Reason reason, int status, String errorCode, String requestId, String detail) {
    StringBuilder sb = new StringBuilder("SecretMoa request failed: reason=").append(reason);
    if (status > 0) {
      sb.append(", status=").append(status);
    }
    if (errorCode != null) {
      sb.append(", error=").append(errorCode);
    }
    if (requestId != null) {
      sb.append(", requestId=").append(requestId);
    }
    if (detail != null) {
      sb.append(", detail=").append(detail);
    }
    return sb.toString();
  }
}
