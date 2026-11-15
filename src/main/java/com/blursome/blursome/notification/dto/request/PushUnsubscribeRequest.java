package com.blursome.blursome.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 이 기기 구독 해제 요청. */
public record PushUnsubscribeRequest(
    @Schema(description = "해제할 구독의 endpoint")
    @NotBlank(message = "endpoint는 필수입니다.")
    String endpoint
) {
}
