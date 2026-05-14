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

  /**
   * 카카오 OAuth 인가 코드로 로그인하고 새 토큰 쌍을 발급한다.
   *
   * <p>인가 코드 → 공급자 사용자 정보 조회 → 회원 조회/생성 → 토큰 발급 순으로
   * 진행하며, 발급된 RefreshToken은 Redis에 저장된다.
   *
   * @param authorizationCode 카카오 인가 화면에서 발급된 1회용 코드
   * @return 새로 발급된 AccessToken·RefreshToken 쌍
   */
  @Transactional
  public TokenPair loginWithKakao(String authorizationCode) {
    OAuthClient client = oAuthClientResolver.resolve(OAuthProvider.KAKAO);
    OAuthUserInfo userInfo = client.fetchUserInfo(authorizationCode);
    Member member = memberService.findOrCreateByOAuth(userInfo);
    return issueTokens(member);
  }

  /**
   * 쿠키의 RefreshToken으로 토큰 쌍을 회전(rotate) 발급한다.
   *
   * <p>저장된 RT와 일치 여부를 검사하며, 불일치 시 보안상 저장된 RT를 즉시 삭제하고
   * 예외를 던진다(탈취 RT 무력화 정책).
   *
   * @param refreshToken HttpOnly 쿠키의 RT 값
   * @return 새로 발급된 토큰 쌍
   * @throws BaseException RT가 부재·미저장·불일치하거나 회원이 비활성 상태인 경우
   * @throws com.blursome.blursome.global.exception.JwtAuthenticationException RT 파싱 실패 시
   */
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

  /**
   * 사용자를 로그아웃 처리한다(멱등).
   *
   * <p>RT가 부재·만료·위조 어느 경우에도 예외 없이 정상 종료한다.
   * 유효한 RT일 때만 Redis에서 해당 회원의 RT 키를 삭제한다.
   *
   * @param refreshToken HttpOnly 쿠키의 RT 값 (null/blank/만료/위조 모두 허용)
   */
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
