package com.blursome.blursome.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.exception.code.JwtErrorCode;
import com.blursome.blursome.member.domain.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private static final String SECRET = "test-secret-key-test-secret-key-test-secret-key-32";

  private JwtTokenProvider tokenProvider;
  private JwtTokenProvider shortLivedTokenProvider;

  @BeforeEach
  void setUp() {
    tokenProvider = new JwtTokenProvider(new JwtProperties(SECRET, 1800L, 1209600L));
    shortLivedTokenProvider = new JwtTokenProvider(new JwtProperties(SECRET, 0L, 0L));
  }

  @Test
  @DisplayName("발급한 AccessToken을 파싱하면 memberId와 role을 복원할 수 있다")
  void parseAccess_whenValidToken_thenReturnsAuthentication() {
    // given
    String token = tokenProvider.issueAccessToken(42L, MemberRole.USER);

    // when
    JwtAuthentication auth = tokenProvider.parseAccess(token);

    // then
    assertThat(auth.getPrincipal()).isEqualTo(42L);
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
  }

  @Test
  @DisplayName("발급한 RefreshToken을 파싱하면 memberId를 복원할 수 있다")
  void parseRefresh_whenValidToken_thenReturnsMemberId() {
    // given
    String token = tokenProvider.issueRefreshToken(42L);

    // when
    Long memberId = tokenProvider.parseRefresh(token);

    // then
    assertThat(memberId).isEqualTo(42L);
  }

  @Test
  @DisplayName("AccessToken으로 RefreshToken을 파싱하면 INVALID_TOKEN이 발생한다")
  void parseRefresh_whenAccessTokenUsed_thenThrowsInvalidToken() {
    // given
    String accessToken = tokenProvider.issueAccessToken(1L, MemberRole.USER);

    // when & then
    assertThatThrownBy(() -> tokenProvider.parseRefresh(accessToken))
        .isInstanceOf(JwtAuthenticationException.class)
        .extracting("errorCode")
        .isEqualTo(JwtErrorCode.INVALID_TOKEN);
  }

  @Test
  @DisplayName("위조된 토큰 파싱 시 INVALID_TOKEN이 발생한다")
  void parseAccess_whenMalformedToken_thenThrowsInvalidToken() {
    // when & then
    assertThatThrownBy(() -> tokenProvider.parseAccess("not-a-jwt"))
        .isInstanceOf(JwtAuthenticationException.class)
        .extracting("errorCode")
        .isEqualTo(JwtErrorCode.INVALID_TOKEN);
  }

  @Test
  @DisplayName("만료된 토큰 파싱 시 EXPIRED_TOKEN이 발생한다")
  void parseAccess_whenExpired_thenThrowsExpiredToken() {
    // given
    String expired = shortLivedTokenProvider.issueAccessToken(1L, MemberRole.USER);

    // when & then
    assertThatThrownBy(() -> shortLivedTokenProvider.parseAccess(expired))
        .isInstanceOf(JwtAuthenticationException.class)
        .extracting("errorCode")
        .isEqualTo(JwtErrorCode.EXPIRED_TOKEN);
  }
}
