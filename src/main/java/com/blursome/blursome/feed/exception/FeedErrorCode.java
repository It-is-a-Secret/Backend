package com.blursome.blursome.feed.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FeedErrorCode implements ErrorCode {

  FEED_NOT_FOUND(HttpStatus.NOT_FOUND,
      "피드를 찾을 수 없습니다.",
      "FEED_404_NOT_FOUND"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
