package com.blursome.blursome.feed.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FeedImageErrorCode implements ErrorCode {

  INVALID_COUNT(HttpStatus.BAD_REQUEST,
      "피드 이미지는 1장 이상 5장 이하여야 합니다.",
      "FEED_IMAGE_400_INVALID_COUNT"
  ),
  DUPLICATE_ORDER(HttpStatus.BAD_REQUEST,
      "피드 이미지 노출 순서가 중복되었습니다.",
      "FEED_IMAGE_400_DUPLICATE_ORDER"
  ),
  NOT_FOUND(HttpStatus.NOT_FOUND,
      "피드 이미지를 찾을 수 없습니다.",
      "FEED_IMAGE_404_NOT_FOUND"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
