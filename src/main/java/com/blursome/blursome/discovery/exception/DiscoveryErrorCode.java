package com.blursome.blursome.discovery.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DiscoveryErrorCode implements ErrorCode {

  DISCOVERY_ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN,
      "온보딩을 완료해야 탐색을 이용할 수 있습니다.",
      "DISCOVERY_403_ONBOARDING_REQUIRED"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
