package com.blursome.blursome.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** VAPID 공개 키. 프론트 {@code pushManager.subscribe({ applicationServerKey })}에 넣는다. */
public record PushPublicKeyResponse(
    @Schema(description = "VAPID 공개 키(base64url)", example = "BEl62iUYgUivxIkv69yViEuiBIa...")
    String publicKey
) {
}
