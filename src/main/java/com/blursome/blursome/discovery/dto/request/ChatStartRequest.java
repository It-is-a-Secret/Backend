package com.blursome.blursome.discovery.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 피드로 1:1 대화를 시작할 때 함께 보내는 첫 메시지 요청(이슈 #87, {@code POST /api/discovery/feeds/{feedId}/chat}).
 *
 * <p>대상은 경로 변수 {@code feedId}로, 발신자는 인증 principal로 결정하므로 본문에는 첫 메시지 본문만 담는다.
 * 본문은 {@code TEXT}로 전송되며, 빈 본문은 거부한다.
 */
public record ChatStartRequest(
    @NotBlank String message
) {

}
