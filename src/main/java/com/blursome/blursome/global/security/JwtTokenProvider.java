package com.blursome.blursome.global.security;

import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.exception.code.JwtErrorCode;
import com.blursome.blursome.member.domain.MemberRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

  private static final String CLAIM_ROLE = "role";
  private static final String TOKEN_TYPE = "typ";
  private static final String TOKEN_TYPE_ACCESS = "access";
  private static final String TOKEN_TYPE_REFRESH = "refresh";

  private final SecretKey key;
  private final long accessTtlSeconds;
  private final long refreshTtlSeconds;

  public JwtTokenProvider(JwtProperties properties) {
    this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    this.accessTtlSeconds = properties.accessTokenExpiresIn();
    this.refreshTtlSeconds = properties.refreshTokenExpiresIn();
  }

  /** 회원 ID와 권한을 클레임으로 담아 AccessToken을 발급한다. */
  public String issueAccessToken(Long memberId, MemberRole role) {
    return buildToken(memberId, TOKEN_TYPE_ACCESS, accessTtlSeconds, role);
  }

  /** 회원 ID를 subject로 담아 RefreshToken을 발급한다(권한 클레임은 포함하지 않음). */
  public String issueRefreshToken(Long memberId) {
    return buildToken(memberId, TOKEN_TYPE_REFRESH, refreshTtlSeconds, null);
  }

  /**
   * AccessToken을 파싱하여 인증 정보를 복원한다.
   *
   * @param token Bearer 헤더에서 추출한 AccessToken 문자열
   * @return 회원 ID(principal)와 권한이 담긴 {@link JwtAuthentication}
   * @throws JwtAuthenticationException 토큰이 유효하지 않거나 만료된 경우
   */
  public JwtAuthentication parseAccess(String token) {
    Claims claims = parseClaims(token, TOKEN_TYPE_ACCESS);
    try {
      Long memberId = Long.parseLong(claims.getSubject());
      MemberRole role = MemberRole.valueOf(claims.get(CLAIM_ROLE, String.class));
      return JwtAuthentication.of(memberId, role);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new JwtAuthenticationException(JwtErrorCode.INVALID_TOKEN);
    }
  }

  /**
   * RefreshToken을 파싱하여 회원 ID를 추출한다.
   *
   * @param token RT 쿠키 값
   * @return 회원 ID
   * @throws JwtAuthenticationException 토큰이 유효하지 않거나 만료된 경우
   */
  public Long parseRefresh(String token) {
    Claims claims = parseClaims(token, TOKEN_TYPE_REFRESH);
    try {
      return Long.parseLong(claims.getSubject());
    } catch (NumberFormatException | NullPointerException e) {
      throw new JwtAuthenticationException(JwtErrorCode.INVALID_TOKEN);
    }
  }

  public long getAccessTtlSeconds() {
    return accessTtlSeconds;
  }

  public long getRefreshTtlSeconds() {
    return refreshTtlSeconds;
  }

  private String buildToken(Long memberId, String type, long ttlSeconds, MemberRole role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + Duration.ofSeconds(ttlSeconds).toMillis());

    var builder = Jwts.builder()
        .subject(String.valueOf(memberId))
        .claim(TOKEN_TYPE, type)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(key, Jwts.SIG.HS256);

    if (role != null) {
      builder.claim(CLAIM_ROLE, role.name());
    }
    return builder.compact();
  }

  private Claims parseClaims(String token, String expectedType) {
    try {
      Claims claims = Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      String type = claims.get(TOKEN_TYPE, String.class);
      if (!expectedType.equals(type)) {
        throw new JwtAuthenticationException(JwtErrorCode.INVALID_TOKEN);
      }
      return claims;
    } catch (ExpiredJwtException e) {
      throw new JwtAuthenticationException(JwtErrorCode.EXPIRED_TOKEN);
    } catch (JwtException | IllegalArgumentException e) {
      throw new JwtAuthenticationException(JwtErrorCode.INVALID_TOKEN);
    }
  }
}
