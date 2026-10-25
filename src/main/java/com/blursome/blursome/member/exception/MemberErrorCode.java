package com.blursome.blursome.member.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

  MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND,
      "회원을 찾을 수 없습니다.",
      "MEMBER_404_NOT_FOUND"
  ),
  MEMBER_INACTIVE(HttpStatus.FORBIDDEN,
      "비활성 회원입니다.",
      "MEMBER_403_INACTIVE"
  ),
  MEMBER_SUSPENDED(HttpStatus.FORBIDDEN,
      "신고 누적으로 정지된 계정입니다. 문의가 필요하면 운영팀에 연락해주세요.",
      "MEMBER_403_SUSPENDED"
  ),
  MEMBER_OAUTH_CONFLICT(HttpStatus.CONFLICT,
      "동일한 OAuth 계정의 가입 요청이 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.",
      "MEMBER_409_OAUTH_CONFLICT"
  ),
  MEMBER_SCHOOL_EMAIL_DUPLICATED(HttpStatus.CONFLICT,
      "이미 사용 중인 학교 이메일입니다.",
      "MEMBER_409_SCHOOL_EMAIL_DUPLICATED"
  ),
  MEMBER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT,
      "이미 사용 중인 닉네임입니다.",
      "MEMBER_409_NICKNAME_DUPLICATED"
  ),
  MEMBER_SCHOOL_VERIFICATION_REQUIRED(HttpStatus.CONFLICT,
      "학교 이메일 인증을 먼저 완료해야 합니다.",
      "MEMBER_409_SCHOOL_VERIFICATION_REQUIRED"
  ),
  MEMBER_ALREADY_VERIFIED(HttpStatus.CONFLICT,
      "이미 학교 인증을 완료한 회원입니다.",
      "MEMBER_409_ALREADY_VERIFIED"
  ),
  MEMBER_ALREADY_ONBOARDED(HttpStatus.CONFLICT,
      "이미 온보딩을 완료한 회원입니다.",
      "MEMBER_409_ALREADY_ONBOARDED"
  ),
  MEMBER_ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN,
      "온보딩을 완료해야 프로필을 수정할 수 있습니다.",
      "MEMBER_403_ONBOARDING_REQUIRED"
  ),
  // 이슈 #95: 프로필 항목별 30일 변경 제한. 프론트가 막힌 항목을 구분하도록 항목별로 둔다.
  MEMBER_NICKNAME_CHANGE_LIMITED(HttpStatus.CONFLICT,
      "닉네임은 30일에 한 번만 변경할 수 있습니다.",
      "MEMBER_409_NICKNAME_CHANGE_LIMITED"
  ),
  MEMBER_MBTI_CHANGE_LIMITED(HttpStatus.CONFLICT,
      "MBTI는 30일에 한 번만 변경할 수 있습니다.",
      "MEMBER_409_MBTI_CHANGE_LIMITED"
  ),
  MEMBER_KEYWORDS_CHANGE_LIMITED(HttpStatus.CONFLICT,
      "관심사는 30일에 한 번만 변경할 수 있습니다.",
      "MEMBER_409_KEYWORDS_CHANGE_LIMITED"
  ),
  MEMBER_ALREADY_WITHDRAWN(HttpStatus.CONFLICT,
      "이미 탈퇴한 회원입니다.",
      "MEMBER_409_ALREADY_WITHDRAWN"
  ),
  // 이슈 #107: 탈퇴 후 30일 동안 같은 카카오 계정·학교 이메일 재가입 제한. 서비스는 재가입 가능 시각을 메시지에 담는다.
  MEMBER_REJOIN_RESTRICTED(HttpStatus.CONFLICT,
      "탈퇴 후 30일 동안은 같은 계정이나 학교 이메일로 다시 가입할 수 없습니다.",
      "MEMBER_409_REJOIN_RESTRICTED"
  ),
  MEMBER_ALREADY_ACTIVE(HttpStatus.CONFLICT,
      "이미 활성 상태인 회원입니다.",
      "MEMBER_409_ALREADY_ACTIVE"
  ),
  MEMBER_INVALID_SCHOOL_EMAIL_DOMAIN(HttpStatus.BAD_REQUEST,
      "안양대학교 학교 이메일(@gs.anyang.ac.kr)만 인증할 수 있습니다.",
      "MEMBER_400_INVALID_SCHOOL_EMAIL_DOMAIN"
  ),
  MEMBER_VERIFICATION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND,
      "발송된 인증 코드가 없거나 만료되었습니다. 코드를 다시 발송해주세요.",
      "MEMBER_404_VERIFICATION_CODE_NOT_FOUND"
  ),
  MEMBER_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST,
      "인증 코드가 일치하지 않습니다.",
      "MEMBER_400_VERIFICATION_CODE_MISMATCH"
  ),
  MEMBER_EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY,
      "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.",
      "MEMBER_502_EMAIL_SEND_FAILED"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
