package com.blursome.blursome.member.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 학교 이메일 인증 코드 발송 요청. 도메인(@gs.anyang.ac.kr) 검증은 서비스 정책에서 수행한다. */
public record SendSchoolEmailCodeRequest(
    @NotBlank(message = "학교 이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String schoolEmail
) {

}
