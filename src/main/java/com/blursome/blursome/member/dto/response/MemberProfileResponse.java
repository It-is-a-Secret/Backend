package com.blursome.blursome.member.dto.response;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.RegistrationStatus;
import java.util.List;

/**
 * 회원 프로필 응답(온보딩 완료 결과·내 정보 조회).
 *
 * <p>공개 프로필(생년·학과·MBTI·성별)은 {@link Feed}가 보유한다. 온보딩 미완료 회원은 피드가 없어
 * {@code feed}가 {@code null}이며, 해당 필드는 모두 {@code null}로 응답한다. 관심사는 회원이 선택한
 * 키워드({@link MemberKeyword}) 목록으로 응답한다.
 */
public record MemberProfileResponse(
    Long id,
    String nickName,
    String schoolEmail,
    Integer birthYear,
    Department department,
    Mbti mbti,
    Gender gender,
    RegistrationStatus registrationStatus,
    List<MemberKeywordResponse> keywords
) {

  public static MemberProfileResponse of(
      Member member, Feed feed, List<MemberKeyword> keywords) {
    return new MemberProfileResponse(
        member.getId(),
        member.getNickName(),
        member.getSchoolEmail(),
        feed == null ? null : feed.getBirthYear(),
        feed == null ? null : feed.getDepartment(),
        feed == null ? null : feed.getMbti(),
        feed == null ? null : feed.getGender(),
        member.getRegistrationStatus(),
        keywords.stream().map(MemberKeywordResponse::from).toList()
    );
  }
}
