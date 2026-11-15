package com.blursome.blursome.notification.dto.request;

import com.blursome.blursome.notification.domain.PushSubscription;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 구독 등록·갱신 요청. 브라우저 {@code PushSubscription.toJSON()} 결과를 그대로 보낸다
 * ({@code expirationTime} 등 나머지 필드는 무시한다).
 */
public record PushSubscriptionRequest(
    @Schema(description = "푸시 서비스 endpoint", example = "https://web.push.apple.com/QGuQyavXut...")
    @NotBlank(message = "endpoint는 필수입니다.")
    @Size(max = PushSubscription.MAX_ENDPOINT_LENGTH, message = "endpoint가 너무 깁니다.")
    String endpoint,

    @NotNull(message = "keys는 필수입니다.")
    @Valid
    Keys keys
) {

  public record Keys(
      @Schema(description = "수신 기기 공개 키(base64url)")
      @NotBlank(message = "keys.p256dh는 필수입니다.")
      @Size(max = PushSubscription.MAX_P256DH_LENGTH, message = "keys.p256dh가 너무 깁니다.")
      String p256dh,

      @Schema(description = "인증 비밀(base64url)")
      @NotBlank(message = "keys.auth는 필수입니다.")
      @Size(max = PushSubscription.MAX_AUTH_LENGTH, message = "keys.auth가 너무 깁니다.")
      String auth
  ) {
  }
}
