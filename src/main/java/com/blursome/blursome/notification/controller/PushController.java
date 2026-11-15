package com.blursome.blursome.notification.controller;

import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.notification.dto.request.PushSubscriptionRequest;
import com.blursome.blursome.notification.dto.request.PushUnsubscribeRequest;
import com.blursome.blursome.notification.dto.response.PushPublicKeyResponse;
import com.blursome.blursome.notification.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Push", description = "PWA Web Push(VAPID) 기기 구독 API")
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {

  private final PushSubscriptionService pushSubscriptionService;

  @Operation(summary = "VAPID 공개 키 조회",
      description = "프론트 pushManager.subscribe({ applicationServerKey })에 넣을 공개 키(base64url)를 반환한다. "
          + "인증 없이 호출할 수 있다.")
  @SecurityRequirements
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "{ \"data\": { \"publicKey\": \"<base64url>\" } }"),
      @ApiResponse(responseCode = "503", description = "서버에 VAPID 키가 설정되지 않음(PUSH_503_NOT_CONFIGURED)")
  })
  @GetMapping("/public-key")
  public ResponseEntity<DataResponse<PushPublicKeyResponse>> getPublicKey() {
    return ResponseEntity.ok(DataResponse.ok(pushSubscriptionService.getPublicKey()));
  }

  @Operation(summary = "이 기기 구독 등록·갱신",
      description = "브라우저 PushSubscription.toJSON() 결과 { endpoint, keys: { p256dh, auth } }를 그대로 보낸다. "
          + "endpoint 기준 upsert라 앱 시작 시마다, 그리고 pushsubscriptionchange 때 다시 보내도 멱등이다. 같은 endpoint를 "
          + "다른 계정이 등록하면 소유 회원이 그 계정으로 바뀐다.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "등록·갱신 완료(바디 없음)"),
      @ApiResponse(responseCode = "400", description = "필수 값 누락·키 형식 오류(PUSH_400_INVALID_KEYS)·"
          + "지원하지 않는 푸시 서비스 주소(PUSH_400_ENDPOINT_NOT_ALLOWED)")
  })
  @PutMapping("/subscriptions")
  public ResponseEntity<Void> subscribe(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody PushSubscriptionRequest request,
      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent
  ) {
    pushSubscriptionService.subscribe(memberId, request, userAgent);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "이 기기 구독 해제",
      description = "알림 끄기용. 프론트는 subscription.unsubscribe()와 함께 호출한다. 구독이 없거나 이미 해제됐어도 204(멱등).")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "해제 완료(바디 없음, 구독이 없어도 204)"),
      @ApiResponse(responseCode = "400", description = "endpoint 누락")
  })
  @DeleteMapping("/subscriptions")
  public ResponseEntity<Void> unsubscribe(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody PushUnsubscribeRequest request
  ) {
    pushSubscriptionService.unsubscribe(memberId, request.endpoint());
    return ResponseEntity.noContent().build();
  }
}
