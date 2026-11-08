package com.blursome.blursome.member.domain;

import com.blursome.blursome.member.exception.MemberErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩 이후 수정 가능한 프로필 항목(#95, 사진 #113, 블러 강도 #118). 30일 변경 제한은 항목별로 적용한다.
 *
 * <p>성별·생년·학과는 탐색·대화 자격의 기준이라 수정 대상이 아니다.
 */
@Getter
@RequiredArgsConstructor
public enum ProfileItem {

  NICKNAME(MemberErrorCode.MEMBER_NICKNAME_CHANGE_LIMITED),
  MBTI(MemberErrorCode.MEMBER_MBTI_CHANGE_LIMITED),
  KEYWORDS(MemberErrorCode.MEMBER_KEYWORDS_CHANGE_LIMITED),
  /** 피드 원본 사진 교체(#113). 순서·블러 변경과 빈 자리 추가는 교체가 아니다(판정은 {@code FeedImageService}). */
  PHOTOS(MemberErrorCode.MEMBER_PHOTOS_CHANGE_LIMITED),
  /**
   * 유지하는 피드 사진의 블러 강도 변경(#118). 새로 올리는 사진의 블러 강도와 처리 실패 사진은 해당하지 않는다(판정은
   * {@code FeedImageService}).
   */
  BLUR_LEVEL(MemberErrorCode.MEMBER_BLUR_LEVEL_CHANGE_LIMITED);

  /** 이 항목이 30일 제한에 걸렸을 때 던지는 에러코드. 프론트가 어떤 항목이 막혔는지 구분할 수 있게 항목별로 둔다. */
  private final MemberErrorCode changeLimitedError;
}
