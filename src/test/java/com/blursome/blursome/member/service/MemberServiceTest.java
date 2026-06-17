package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.domain.RegistrationStatus;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @InjectMocks
  private MemberService memberService;

  @Test
  @DisplayName("기존 회원이 없으면 새로 저장한다")
  void findOrCreateByOAuth_whenMemberAbsent_thenSavesNew() {
    // given
    OAuthUserInfo userInfo = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "test@example.com", "blur", "https://img");
    given(memberRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.empty());
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    Member result = memberService.findOrCreateByOAuth(userInfo);

    // then
    assertThat(result.getProvider()).isEqualTo(OAuthProvider.KAKAO);
    assertThat(result.getProviderId()).isEqualTo("kakao-1");
    assertThat(result.getName()).isEqualTo("blur");
    assertThat(result.getRole()).isEqualTo(MemberRole.USER);
    assertThat(result.getActivityStatus()).isEqualTo(ActivityStatus.ACTIVE);
    assertThat(result.getRegistrationStatus()).isEqualTo(RegistrationStatus.UNVERIFIED);
    verify(memberRepository).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("동시 가입으로 유니크 제약이 깨지면 MEMBER_OAUTH_CONFLICT 예외가 발생한다")
  void findOrCreateByOAuth_whenUniqueConflict_thenThrowsConflict() {
    // given
    OAuthUserInfo userInfo = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "test@example.com", "blur", "https://img");
    given(memberRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.empty());
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willThrow(new DataIntegrityViolationException(
            "duplicate key",
            new ConstraintViolationException(
                "duplicate", new SQLException("dup"), "uk_member_provider")));

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(userInfo))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_OAUTH_CONFLICT.getCode());
  }

  @Test
  @DisplayName("provider 충돌이 아닌 무결성 위반은 충돌로 변환하지 않고 그대로 전파한다")
  void findOrCreateByOAuth_whenOtherIntegrityViolation_thenRethrows() {
    // given
    OAuthUserInfo userInfo = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "test@example.com", "blur", "https://img");
    given(memberRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.empty());
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willThrow(new DataIntegrityViolationException("Column 'foo' cannot be null"));

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(userInfo))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("기존 회원이 있으면 프로필을 갱신만 하고 새로 저장하지 않는다")
  void findOrCreateByOAuth_whenMemberExists_thenUpdatesProfile() {
    // given
    Member existing = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "old-name", "old@example.com", "old-img");
    given(memberRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.of(existing));
    OAuthUserInfo userInfo = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "old@example.com", "new-name", "new-img");

    // when
    Member result = memberService.findOrCreateByOAuth(userInfo);

    // then
    assertThat(result.getName()).isEqualTo("new-name");
    assertThat(result.getProfileImageUrl()).isEqualTo("new-img");
    verify(memberRepository, never()).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("존재하지 않는 회원 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
  void findActiveMember_whenMemberNotFound_thenThrows() {
    // given
    given(memberRepository.findById(99L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> memberService.findActiveMember(99L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("비활성 회원 조회 시 MEMBER_INACTIVE 예외가 발생한다")
  void findActiveMember_whenMemberInactive_thenThrows() {
    // given
    Member inactive = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "nick", "e@e.com", null);
    ReflectionTestUtils.setField(inactive, "activityStatus", ActivityStatus.WITHDRAWN);
    given(memberRepository.findById(1L)).willReturn(Optional.of(inactive));

    // when & then
    assertThatThrownBy(() -> memberService.findActiveMember(1L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_INACTIVE.getCode());
  }
}
