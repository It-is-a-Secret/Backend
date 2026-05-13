package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.MemberStatus;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    given(memberRepository.save(any(Member.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    Member result = memberService.findOrCreateByOAuth(userInfo);

    // then
    assertThat(result.getProvider()).isEqualTo(OAuthProvider.KAKAO);
    assertThat(result.getProviderId()).isEqualTo("kakao-1");
    assertThat(result.getNickname()).isEqualTo("blur");
    assertThat(result.getRole()).isEqualTo(MemberRole.USER);
    assertThat(result.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    verify(memberRepository).save(any(Member.class));
  }

  @Test
  @DisplayName("기존 회원이 있으면 프로필을 갱신만 하고 새로 저장하지 않는다")
  void findOrCreateByOAuth_whenMemberExists_thenUpdatesProfile() {
    // given
    Member existing = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "old@example.com", "old-nick", "old-img");
    given(memberRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.of(existing));
    OAuthUserInfo userInfo = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "old@example.com", "new-nick", "new-img");

    // when
    Member result = memberService.findOrCreateByOAuth(userInfo);

    // then
    assertThat(result.getNickname()).isEqualTo("new-nick");
    assertThat(result.getProfileImageUrl()).isEqualTo("new-img");
    verify(memberRepository, never()).save(any(Member.class));
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
        OAuthProvider.KAKAO, "kakao-1", "e@e.com", "nick", null);
    ReflectionTestUtils.setField(inactive, "status", MemberStatus.INACTIVE);
    given(memberRepository.findById(1L)).willReturn(Optional.of(inactive));

    // when & then
    assertThatThrownBy(() -> memberService.findActiveMember(1L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_INACTIVE.getCode());
  }
}
