package com.blursome.blursome.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.oauth.OAuthClient;
import com.blursome.blursome.auth.oauth.OAuthClientResolver;
import com.blursome.blursome.auth.token.RefreshTokenStore;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.service.MemberService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock
  private OAuthClientResolver oAuthClientResolver;
  @Mock
  private MemberService memberService;
  @Mock
  private JwtTokenProvider jwtTokenProvider;
  @Mock
  private RefreshTokenStore refreshTokenStore;
  @Mock
  private OAuthClient kakaoClient;

  @InjectMocks
  private AuthService authService;

  private Member sampleMember() {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "e@e.com", "blur", null);
    ReflectionTestUtils.setField(member, "id", 1L);
    return member;
  }

  @Test
  @DisplayName("카카오 로그인 시 OAuth 사용자 정보로 회원을 조회/생성하고 토큰을 발급한다")
  void loginWithKakao_whenValidCode_thenIssuesTokens() {
    // given
    OAuthUserInfo info = new OAuthUserInfo(
        OAuthProvider.KAKAO, "kakao-1", "e@e.com", "blur", "img");
    given(oAuthClientResolver.resolve(OAuthProvider.KAKAO)).willReturn(kakaoClient);
    given(kakaoClient.fetchUserInfo("code-abc")).willReturn(info);
    Member member = sampleMember();
    given(memberService.findOrCreateByOAuth(info)).willReturn(member);
    given(jwtTokenProvider.issueAccessToken(1L, MemberRole.USER)).willReturn("access");
    given(jwtTokenProvider.issueRefreshToken(1L)).willReturn("refresh");
    given(jwtTokenProvider.getAccessTtlSeconds()).willReturn(1800L);
    given(jwtTokenProvider.getRefreshTtlSeconds()).willReturn(1209600L);

    // when
    TokenPair result = authService.loginWithKakao("code-abc");

    // then
    assertThat(result.accessToken()).isEqualTo("access");
    assertThat(result.refreshToken()).isEqualTo("refresh");
    verify(refreshTokenStore).save(1L, "refresh", 1209600L);
  }

  @Test
  @DisplayName("null 또는 빈 RefreshToken이면 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
  void refresh_whenTokenNullOrBlank_thenThrowsNotFound() {
    assertThatThrownBy(() -> authService.refresh(null))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", AuthErrorCode.REFRESH_TOKEN_NOT_FOUND.getCode());
    assertThatThrownBy(() -> authService.refresh("  "))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", AuthErrorCode.REFRESH_TOKEN_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("저장된 RefreshToken이 없으면 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
  void refresh_whenStoredTokenMissing_thenThrowsNotFound() {
    // given
    given(jwtTokenProvider.parseRefresh("refresh")).willReturn(1L);
    given(refreshTokenStore.find(1L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> authService.refresh("refresh"))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", AuthErrorCode.REFRESH_TOKEN_NOT_FOUND.getCode());
    verify(refreshTokenStore, never()).save(anyLong(), anyString(), anyLong());
  }

  @Test
  @DisplayName("저장된 RefreshToken과 일치하지 않으면 MISMATCH 예외가 발생하고 저장소가 비워진다")
  void refresh_whenStoredTokenMismatch_thenThrowsAndDeletes() {
    // given
    given(jwtTokenProvider.parseRefresh("refresh")).willReturn(1L);
    given(refreshTokenStore.find(1L)).willReturn(Optional.of("different-refresh"));

    // when & then
    assertThatThrownBy(() -> authService.refresh("refresh"))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", AuthErrorCode.REFRESH_TOKEN_MISMATCH.getCode());
    verify(refreshTokenStore).delete(1L);
  }

  @Test
  @DisplayName("RefreshToken이 일치하면 새 토큰이 발급되고 저장소가 갱신된다")
  void refresh_whenStoredTokenMatches_thenIssuesNewPair() {
    // given
    given(jwtTokenProvider.parseRefresh("refresh-old")).willReturn(1L);
    given(refreshTokenStore.find(1L)).willReturn(Optional.of("refresh-old"));
    Member member = sampleMember();
    given(memberService.findActiveMember(1L)).willReturn(member);
    given(jwtTokenProvider.issueAccessToken(1L, MemberRole.USER)).willReturn("access-new");
    given(jwtTokenProvider.issueRefreshToken(1L)).willReturn("refresh-new");
    given(jwtTokenProvider.getRefreshTtlSeconds()).willReturn(1209600L);

    // when
    TokenPair result = authService.refresh("refresh-old");

    // then
    assertThat(result.accessToken()).isEqualTo("access-new");
    assertThat(result.refreshToken()).isEqualTo("refresh-new");
    verify(refreshTokenStore).save(1L, "refresh-new", 1209600L);
  }

  @Test
  @DisplayName("로그아웃 시 RefreshTokenStore에서 토큰이 제거된다")
  void logout_thenDeletesRefreshToken() {
    // when
    authService.logout(1L);

    // then
    verify(refreshTokenStore).delete(eq(1L));
  }
}
