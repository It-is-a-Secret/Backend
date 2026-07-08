package com.blursome.blursome.keyword.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum KeywordErrorCode implements ErrorCode {

  KEYWORD_TAG_NOT_FOUND(HttpStatus.BAD_REQUEST,
      "존재하지 않거나 비활성화된 키워드 태그가 포함되어 있습니다.",
      "KEYWORD_400_TAG_NOT_FOUND"
  ),
  KEYWORD_REQUIRED_CATEGORY_MISSING(HttpStatus.BAD_REQUEST,
      "필수 키워드 카테고리에서 최소 1개를 선택해야 합니다.",
      "KEYWORD_400_REQUIRED_CATEGORY_MISSING"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
