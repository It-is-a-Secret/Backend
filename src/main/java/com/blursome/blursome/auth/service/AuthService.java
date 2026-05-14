package com.blursome.blursome.auth.service;

import com.blursome.blursome.auth.dto.TokenPair;
import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.oauth.OAuthClient;
import com.blursome.blursome.auth.oauth.OAuthClientResolver;
import com.blursome.blursome.auth.token.RefreshTokenStore;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

  private final OAuthClientResolver oAuthClientResolver;
  private final MemberService memberService;
  private final JwtTokenProvider jwtTokenProvider;
  private final RefreshTokenStore refreshTokenStore;

  @Transactional
  public TokenPair loginWithKakao(String authorizationCode) {
    OAuthClient client = oAuthClientResolver.resolve(OAuthProvider.KAKAO);
    OAuthUserInfo userInfo = client.fetchUserInfo(authorizationCode);
    Member member = memberService.findOrCreateByOAuth(userInfo);
    return issueTokens(member);
  }

  @Transactional
  public TokenPair refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw BaseException.from(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
    Long memberId = jwtTokenProvider.parseRefresh(refreshToken);
    String stored = refreshTokenStore.find(memberId)
        .orElseThrow(() -> BaseException.from(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    if (!stored.equals(refreshToken)) {
      refreshTokenStore.delete(memberId);
      throw BaseException.from(AuthErrorCode.REFRESH_TOKEN_MISMATCH);
    }
    Member member = memberService.findActiveMember(memberId);
    return issueTokens(member);
  }

  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    try {
      Long memberId = jwtTokenProvider.parseRefresh(refreshToken);
      refreshTokenStore.delete(memberId);
    } catch (JwtAuthenticationException ignored) {
      // 만료/위조된 RT여도 로그아웃은 멱등하게 성공 처리한다.
    }
  }

  private TokenPair issueTokens(Member member) {
    String accessToken = jwtTokenProvider.issueAccessToken(member.getId(), member.getRole());
    String refreshToken = jwtTokenProvider.issueRefreshToken(member.getId());
    refreshTokenStore.save(member.getId(), refreshToken, jwtTokenProvider.getRefreshTtlSeconds());
    return new TokenPair(
        accessToken,
        refreshToken,
        jwtTokenProvider.getAccessTtlSeconds(),
        jwtTokenProvider.getRefreshTtlSeconds()
    );
  }
}
