package com.blursome.blursome.block.dto.request;

import jakarta.validation.constraints.NotNull;

/** 차단 등록 요청. 차단 대상 회원 id. */
public record BlockRequest(
    @NotNull(message = "차단 대상 회원 id는 필수입니다.")
    Long targetMemberId
) {

}
