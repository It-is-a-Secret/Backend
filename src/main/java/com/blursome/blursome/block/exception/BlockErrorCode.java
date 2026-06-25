package com.blursome.blursome.block.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BlockErrorCode implements ErrorCode {

  BLOCK_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
      "자기 자신은 차단할 수 없습니다.",
      "BLOCK_400_SELF_NOT_ALLOWED"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
