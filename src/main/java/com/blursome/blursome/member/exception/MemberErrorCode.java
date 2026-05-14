package com.blursome.blursome.member.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

  MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.", "MEMBER_404_NOT_FOUND"),
  MEMBER_INACTIVE(HttpStatus.FORBIDDEN, "비활성 회원입니다.", "MEMBER_403_INACTIVE"),
  MEMBER_OAUTH_CONFLICT(HttpStatus.CONFLICT,
      "동일한 OAuth 계정의 가입 요청이 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.",
      "MEMBER_409_OAUTH_CONFLICT");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
