package com.blursome.blursome.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그아웃 선택 body(#121). 바디가 없어도 로그아웃은 그대로 동작한다.
 *
 * @param endpoint 이 기기의 Web Push 구독 endpoint. 있으면 로그아웃과 함께 그 기기 구독을 해제한다.
 */
public record LogoutRequest(
    @Schema(description = "이 기기의 Web Push 구독 endpoint(선택)", nullable = true)
    String endpoint
) {
}
