package com.blursome.blursome.member.domain;

import com.blursome.blursome.member.exception.MemberErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩 이후 수정 가능한 프로필 항목(#95). 30일 변경 제한은 항목별로 적용한다.
 *
 * <p>성별·생년·학과는 탐색·대화 자격의 기준이라 수정 대상이 아니다.
 */
@Getter
@RequiredArgsConstructor
public enum ProfileItem {

  NICKNAME(MemberErrorCode.MEMBER_NICKNAME_CHANGE_LIMITED),
  MBTI(MemberErrorCode.MEMBER_MBTI_CHANGE_LIMITED),
  KEYWORDS(MemberErrorCode.MEMBER_KEYWORDS_CHANGE_LIMITED);

  /** 이 항목이 30일 제한에 걸렸을 때 던지는 에러코드. 프론트가 어떤 항목이 막혔는지 구분할 수 있게 항목별로 둔다. */
  private final MemberErrorCode changeLimitedError;
}
