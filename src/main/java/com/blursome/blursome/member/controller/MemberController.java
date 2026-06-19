package com.blursome.blursome.member.controller;

import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.member.dto.request.OnboardingRequest;
import com.blursome.blursome.member.dto.request.SendSchoolEmailCodeRequest;
import com.blursome.blursome.member.dto.request.VerifySchoolEmailRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.service.MemberOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "회원 온보딩 API (학교 이메일 인증 → 프로필 작성)")
@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberController {

  private final MemberOnboardingService memberOnboardingService;

  @Operation(summary = "내 프로필 조회", description = "닉네임·생년·학과·MBTI·관심사·가입 단계를 반환한다.")
  @GetMapping
  public ResponseEntity<DataResponse<MemberProfileResponse>> getMyProfile(
      @AuthenticationPrincipal Long memberId
  ) {
    return ResponseEntity.ok(DataResponse.ok(memberOnboardingService.getMyProfile(memberId)));
  }

  @Operation(summary = "학교 이메일 인증 코드 발송",
      description = "안양대학교 이메일(@gs.anyang.ac.kr)로 6자리 인증 코드를 발송한다.")
  @PostMapping("/school-email/verification-codes")
  public ResponseEntity<Void> sendSchoolEmailCode(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody SendSchoolEmailCodeRequest request
  ) {
    memberOnboardingService.sendSchoolEmailVerificationCode(memberId, request.schoolEmail());
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "학교 이메일 인증 코드 검증",
      description = "발송된 코드를 검증하고 학교 인증을 완료한다(UNVERIFIED → VERIFIED).")
  @PostMapping("/school-email/verifications")
  public ResponseEntity<Void> verifySchoolEmail(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody VerifySchoolEmailRequest request
  ) {
    memberOnboardingService.verifySchoolEmail(memberId, request.schoolEmail(), request.code());
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "온보딩 완료",
      description = "닉네임·생년·학과·MBTI·성별·관심사를 저장하고 가입을 완료한다(VERIFIED → COMPLETED).")
  @PostMapping("/onboarding")
  public ResponseEntity<DataResponse<MemberProfileResponse>> completeOnboarding(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody OnboardingRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(memberOnboardingService.completeOnboarding(memberId, request)));
  }
}
