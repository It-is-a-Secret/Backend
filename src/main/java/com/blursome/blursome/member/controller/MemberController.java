package com.blursome.blursome.member.controller;

import com.blursome.blursome.auth.cookie.RefreshTokenCookieFactory;
import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.member.dto.request.OnboardingRequest;
import com.blursome.blursome.member.dto.request.ProfileUpdateRequest;
import com.blursome.blursome.member.dto.request.SendSchoolEmailCodeRequest;
import com.blursome.blursome.member.dto.request.VerifySchoolEmailRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.dto.response.NickNameAvailabilityResponse;
import com.blursome.blursome.member.service.MemberOnboardingService;
import com.blursome.blursome.member.service.MemberProfileService;
import com.blursome.blursome.member.service.MemberWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "회원 API (학교 이메일 인증 → 온보딩 → 프로필 조회·수정 → 탈퇴)")
@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberController {

  private final MemberOnboardingService memberOnboardingService;
  private final MemberProfileService memberProfileService;
  private final MemberWithdrawalService memberWithdrawalService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

  @Operation(summary = "내 프로필 조회",
      description = "닉네임·생년·학과·MBTI·관심사·가입 단계와, 수정 가능 항목(닉네임·MBTI·관심사)별 30일 변경 제한 상태"
          + "(profileEdit: lastChangedAt·nextChangeableAt·changeable)를 반환한다. 온보딩 이후 변경한 적 없는 항목은 "
          + "두 시각이 null이고 즉시 변경 가능하다. 온보딩 미완료 회원은 수정 대상이 아니므로 profileEdit이 null이다.")
  @GetMapping
  public ResponseEntity<DataResponse<MemberProfileResponse>> getMyProfile(
      @AuthenticationPrincipal Long memberId
  ) {
    return ResponseEntity.ok(DataResponse.ok(memberProfileService.getMyProfile(memberId)));
  }

  @Operation(summary = "프로필 수정",
      description = "온보딩 이후 수정 가능한 항목(닉네임·MBTI·관심사) 전체를 받아, 현재 값과 다른 항목만 반영한다. "
          + "항목별로 30일에 한 번만 변경할 수 있고 실제로 바뀐 항목의 변경일만 갱신된다. 바뀐 항목 중 하나라도 제한에 "
          + "걸리면 아무것도 반영하지 않는다(409 NICKNAME/MBTI/KEYWORDS_CHANGE_LIMITED). mbti=null은 \"모름\". "
          + "성별·생년·학과는 수정 대상이 아니며 보내도 무시한다. 닉네임 중복 409, 온보딩 전 403.")
  @PutMapping("/profile")
  public ResponseEntity<DataResponse<MemberProfileResponse>> updateProfile(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody ProfileUpdateRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(memberProfileService.updateProfile(memberId, request)));
  }

  @Operation(summary = "닉네임 사용 가능 여부 확인",
      description = "다른 회원이 사용 중인 닉네임인지 확인한다. 본인의 현재 닉네임은 available=true. 온보딩 중에도 "
          + "사용할 수 있다. 조회 시점 결과이며, 온보딩·프로필 수정 저장 시 서버가 다시 검증한다.")
  @GetMapping("/nickname-availability")
  public ResponseEntity<DataResponse<NickNameAvailabilityResponse>> checkNickNameAvailability(
      @AuthenticationPrincipal Long memberId,
      @Parameter(description = "확인할 닉네임(1~30자)")
      @RequestParam
      @NotBlank(message = "닉네임은 필수입니다.")
      @Size(max = 30, message = "닉네임은 30자 이하여야 합니다.")
      String nickName
  ) {
    return ResponseEntity.ok(DataResponse.ok(
        memberProfileService.checkNickNameAvailability(memberId, nickName)));
  }

  @Operation(summary = "회원 탈퇴",
      description = "인증 회원 본인을 탈퇴시킨다. 요청 본문은 없고 성공 시 204와 함께 refreshToken 쿠키를 만료(Max-Age=0)시킨다.\n\n"
          + "- 계정과 프로필이 비활성화되어 탐색·공개 피드·카드 상세·채팅 개설 대상에서 빠진다(회원 행·피드·사진·메시지·"
          + "신고/차단 이력은 삭제하지 않음).\n"
          + "- 참여 중인 대화는 종료된다(ACTIVE·BLOCKED 방은 CLOSED, 신고 검토 중인 REPORTED 방은 상태 유지). 탈퇴 회원은 "
          + "모든 방에서 나가며, 상대는 지난 이력만 볼 수 있고 새 메시지를 보낼 수 없다.\n"
          + "- 서버의 Refresh Token과 진행 중인 학교 이메일 인증 코드를 지운다. 기존 Access Token으로 보호 API를 호출하거나 "
          + "STOMP에 연결하면 403 MEMBER_403_INACTIVE다(다시 탈퇴를 요청해도 같다).\n"
          + "- 탈퇴 후 30일 동안 같은 카카오 계정으로 로그인하거나 같은 학교 이메일을 인증하면 409 "
          + "MEMBER_409_REJOIN_RESTRICTED이며, 메시지에 가입 가능 시각이 담긴다. 정확히 30일이 지난 시점부터 새 회원으로 "
          + "다시 가입할 수 있다(학교 인증·온보딩부터 다시).\n"
          + "- 신고 누적으로 정지된 회원은 탈퇴할 수 없다(403 MEMBER_403_INACTIVE).")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @DeleteMapping
  public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long memberId) {
    memberWithdrawalService.withdraw(memberId);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.delete().toString())
        .build();
  }

  @Operation(summary = "학교 이메일 인증 코드 발송",
      description = "안양대학교 이메일(@gs.anyang.ac.kr)로 6자리 인증 코드를 발송한다. 이메일은 공백을 지우고 소문자로 "
          + "비교·저장한다. 다른 회원이 사용 중이면 409 SCHOOL_EMAIL_DUPLICATED, 탈퇴 후 30일이 지나지 않은 회원의 "
          + "이메일이면 409 REJOIN_RESTRICTED다.")
  @ResponseStatus(HttpStatus.NO_CONTENT)
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
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PostMapping("/school-email/verifications")
  public ResponseEntity<Void> verifySchoolEmail(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody VerifySchoolEmailRequest request
  ) {
    memberOnboardingService.verifySchoolEmail(memberId, request.schoolEmail(), request.code());
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "온보딩 완료",
      description = "닉네임·생년·학과·MBTI·성별·관심사를 저장하고 가입을 완료한다(VERIFIED → COMPLETED). "
          + "가입 단계 전이 전용이라 완료 후 재호출은 409 ALREADY_ONBOARDED이며, 이후 수정은 PUT /profile을 쓴다.")
  @PostMapping("/onboarding")
  public ResponseEntity<DataResponse<MemberProfileResponse>> completeOnboarding(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody OnboardingRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(memberOnboardingService.completeOnboarding(memberId, request)));
  }
}
