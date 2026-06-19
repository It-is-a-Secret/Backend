package com.blursome.blursome.member.dto.request;

import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.InterestCategoryType;
import com.blursome.blursome.member.domain.Mbti;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 온보딩(프로필 작성) 요청. 학교 이메일 인증 완료(VERIFIED) 회원만 사용한다.
 *
 * <p>관심사는 고정 카탈로그 {@link InterestCategoryType} 값의 목록으로 받으며, 각 행의 설명은
 * 카탈로그 기본값으로 저장된다.
 */
public record OnboardingRequest(
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(max = 30, message = "닉네임은 30자 이하여야 합니다.")
    String nickName,

    @NotNull(message = "생년은 필수입니다.")
    @Min(value = 1900, message = "생년이 올바르지 않습니다.")
    @Max(value = 2100, message = "생년이 올바르지 않습니다.")
    Integer birthYear,

    @NotBlank(message = "학과는 필수입니다.")
    @Size(max = 50, message = "학과는 50자 이하여야 합니다.")
    String department,

    @NotNull(message = "MBTI는 필수입니다.")
    Mbti mbti,

    @NotNull(message = "성별은 필수입니다.")
    Gender gender,

    @NotEmpty(message = "관심사는 하나 이상 선택해야 합니다.")
    List<InterestCategoryType> interests
) {

}
