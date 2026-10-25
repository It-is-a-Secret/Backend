package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.service.MemberKeywordService;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.member.verification.EmailVerificationSender;
import com.blursome.blursome.member.verification.SchoolEmailPolicy;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore.VerificationEntry;
import com.blursome.blursome.member.verification.VerificationCodeGenerator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 학교 이메일 인증의 정규화와 30일 재가입 제한 검증 위치(#107)를 확인한다. */
@ExtendWith(MockitoExtension.class)
class MemberOnboardingServiceTest {

  private static final Long MEMBER_ID = 1L;
  private static final String NORMALIZED = "student@gs.anyang.ac.kr";

  @Mock
  private MemberService memberService;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private MemberKeywordService memberKeywordService;

  @Mock
  private VerificationCodeGenerator verificationCodeGenerator;

  @Mock
  private SchoolEmailVerificationStore verificationStore;

  @Mock
  private EmailVerificationSender emailVerificationSender;

  @Mock
  private FeedService feedService;

  private MemberOnboardingService onboardingService;

  @BeforeEach
  void setUp() {
    onboardingService = new MemberOnboardingService(memberService, memberRepository,
        memberKeywordService, new SchoolEmailPolicy(), verificationCodeGenerator, verificationStore,
        emailVerificationSender, feedService);
  }

  @Test
  @DisplayName("인증 코드 발송은 공백 제거·소문자로 정규화한 이메일로 보유자를 확인하고 그 값으로 저장·발송한다")
  void sendCode_normalizesEmailAndValidatesHolder() {
    // given
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(newMember());
    given(verificationCodeGenerator.generate()).willReturn("123456");

    // when
    onboardingService.sendSchoolEmailVerificationCode(MEMBER_ID, "  Student@GS.Anyang.ac.kr ");

    // then
    verify(memberService).validateSchoolEmailAvailable(MEMBER_ID, NORMALIZED);
    verify(verificationStore).save(MEMBER_ID, NORMALIZED, "123456",
        SchoolEmailVerificationStore.CODE_TTL);
    verify(emailVerificationSender).send(NORMALIZED, "123456");
  }

  @Test
  @DisplayName("탈퇴 후 30일 미만인 회원의 이메일이면 코드를 저장·발송하지 않는다")
  void sendCode_whenRejoinRestricted_thenDoesNotSend() {
    // given
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(newMember());
    willThrow(BaseException.from(MemberErrorCode.MEMBER_REJOIN_RESTRICTED))
        .given(memberService).validateSchoolEmailAvailable(MEMBER_ID, NORMALIZED);

    // when & then
    assertThatThrownBy(() ->
        onboardingService.sendSchoolEmailVerificationCode(MEMBER_ID, NORMALIZED))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
    verify(verificationStore, never()).save(any(), anyString(), anyString(), any());
    verify(emailVerificationSender, never()).send(anyString(), anyString());
  }

  @Test
  @DisplayName("코드 검증은 본인 행을 잠그고, 저장 직전에 보유자를 다시 확인·해제한 뒤 정규화한 이메일을 저장한다")
  void verify_locksSelfRechecksHolderAndStoresNormalizedEmail() {
    // given
    Member member = newMember();
    given(memberService.findActiveMemberForUpdate(MEMBER_ID)).willReturn(member);
    given(verificationStore.find(MEMBER_ID))
        .willReturn(Optional.of(new VerificationEntry(NORMALIZED, "123456")));

    // when
    onboardingService.verifySchoolEmail(MEMBER_ID, " STUDENT@gs.anyang.ac.kr", "123456");

    // then
    InOrder order = inOrder(memberService, memberRepository, verificationStore);
    order.verify(memberService).findActiveMemberForUpdate(MEMBER_ID);
    order.verify(memberService).releaseSchoolEmailForReuse(MEMBER_ID, NORMALIZED);
    order.verify(memberRepository).flush();
    order.verify(verificationStore).delete(MEMBER_ID);
    assertThat(member.getSchoolEmail()).isEqualTo(NORMALIZED);
    assertThat(member.isSchoolVerified()).isTrue();
  }

  @Test
  @DisplayName("저장 직전 재확인에서 재가입 제한이면 학교 인증을 완료하지 않는다")
  void verify_whenRejoinRestrictedAtSave_thenKeepsUnverified() {
    // given
    Member member = newMember();
    given(memberService.findActiveMemberForUpdate(MEMBER_ID)).willReturn(member);
    given(verificationStore.find(MEMBER_ID))
        .willReturn(Optional.of(new VerificationEntry(NORMALIZED, "123456")));
    willThrow(BaseException.from(MemberErrorCode.MEMBER_REJOIN_RESTRICTED))
        .given(memberService).releaseSchoolEmailForReuse(MEMBER_ID, NORMALIZED);

    // when & then
    assertThatThrownBy(() -> onboardingService.verifySchoolEmail(MEMBER_ID, NORMALIZED, "123456"))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
    assertThat(member.isSchoolVerified()).isFalse();
    verify(verificationStore, never()).delete(any());
  }

  private static Member newMember() {
    return Member.createOAuthMember(OAuthProvider.KAKAO, "kakao-1", "blur", null, null);
  }
}
