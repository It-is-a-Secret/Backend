package com.blursome.blursome.member.dto.response;

import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.InterestCategory;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.RegistrationStatus;
import java.util.List;

/** 회원 프로필 응답(온보딩 완료 결과·내 정보 조회). */
public record MemberProfileResponse(
    Long id,
    String nickName,
    String schoolEmail,
    Integer birthYear,
    String department,
    Mbti mbti,
    Gender gender,
    RegistrationStatus registrationStatus,
    List<InterestResponse> interests
) {

  public static MemberProfileResponse of(Member member, List<InterestCategory> interests) {
    return new MemberProfileResponse(
        member.getId(),
        member.getNickName(),
        member.getSchoolEmail(),
        member.getBirthYear(),
        member.getDepartment(),
        member.getMbti(),
        member.getGender(),
        member.getRegistrationStatus(),
        interests.stream().map(InterestResponse::from).toList()
    );
  }
}
