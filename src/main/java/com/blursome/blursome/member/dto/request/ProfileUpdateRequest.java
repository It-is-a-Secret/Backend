package com.blursome.blursome.member.dto.request;

import com.blursome.blursome.member.domain.Mbti;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 온보딩 이후 프로필 수정 요청(#95, {@code PUT /api/members/me/profile}).
 *
 * <p>수정 가능한 항목 <b>전체</b>를 받는다. 서버가 현재 값과 비교해 실제로 바뀐 항목만 반영하고, 그 항목에만
 * 30일 변경 제한을 적용한다. MBTI는 {@code null}이 "모름"이라는 유효한 값이므로 "미변경"을 {@code null}로 표현하는
 * 부분 수정(PATCH) 대신 전체 전송을 쓴다. 성별·생년·학과는 수정 대상이 아니며 요청에 섞여 와도 무시한다.
 */
public record ProfileUpdateRequest(
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(max = 30, message = "닉네임은 30자 이하여야 합니다.")
    String nickName,

    // 선택값. null은 "모름"(#76).
    Mbti mbti,

    @NotEmpty(message = "관심사 키워드는 하나 이상 선택해야 합니다.")
    List<@NotNull(message = "관심사 키워드 id가 올바르지 않습니다.") Long> keywordTagIds
) {

}
