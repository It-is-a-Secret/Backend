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
      "MEMBER_409_OAUTH_CONFLICT"),
  MEMBER_SCHOOL_EMAIL_DUPLICATED(HttpStatus.CONFLICT,
      "이미 사용 중인 학교 이메일입니다.", "MEMBER_409_SCHOOL_EMAIL_DUPLICATED"),
  MEMBER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT,
      "이미 사용 중인 닉네임입니다.", "MEMBER_409_NICKNAME_DUPLICATED"),
  MEMBER_SCHOOL_VERIFICATION_REQUIRED(HttpStatus.CONFLICT,
      "학교 이메일 인증을 먼저 완료해야 합니다.", "MEMBER_409_SCHOOL_VERIFICATION_REQUIRED"),
  MEMBER_ALREADY_VERIFIED(HttpStatus.CONFLICT,
      "이미 학교 인증을 완료한 회원입니다.", "MEMBER_409_ALREADY_VERIFIED"),
  MEMBER_ALREADY_ONBOARDED(HttpStatus.CONFLICT,
      "이미 온보딩을 완료한 회원입니다.", "MEMBER_409_ALREADY_ONBOARDED"),
  MEMBER_ALREADY_WITHDRAWN(HttpStatus.CONFLICT,
      "이미 탈퇴한 회원입니다.", "MEMBER_409_ALREADY_WITHDRAWN"),
  MEMBER_ALREADY_ACTIVE(HttpStatus.CONFLICT,
      "이미 활성 상태인 회원입니다.", "MEMBER_409_ALREADY_ACTIVE");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
