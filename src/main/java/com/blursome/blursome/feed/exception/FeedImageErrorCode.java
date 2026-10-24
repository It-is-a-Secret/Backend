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
  INVALID_OBJECT_KEY(HttpStatus.BAD_REQUEST,
      "유효하지 않은 원본 객체 key입니다.",
      "FEED_IMAGE_400_INVALID_OBJECT_KEY"
  ),
  INVALID_REFERENCE(HttpStatus.BAD_REQUEST,
      "사진은 id와 objectKey 중 하나만 지정해야 합니다.",
      "FEED_IMAGE_400_INVALID_REFERENCE"
  ),
  DUPLICATE_REFERENCE(HttpStatus.BAD_REQUEST,
      "같은 사진을 한 요청에서 두 번 지정할 수 없습니다.",
      "FEED_IMAGE_400_DUPLICATE_REFERENCE"
  ),
  NOT_FOUND(HttpStatus.NOT_FOUND,
      "피드 이미지를 찾을 수 없습니다.",
      "FEED_IMAGE_404_NOT_FOUND"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
