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

  public String issueAccessToken(Long memberId, MemberRole role) {
    return buildToken(memberId, TOKEN_TYPE_ACCESS, accessTtlSeconds, role);
  }

  public String issueRefreshToken(Long memberId) {
    return buildToken(memberId, TOKEN_TYPE_REFRESH, refreshTtlSeconds, null);
  }

  public JwtAuthentication parseAccess(String token) {
    Claims claims = parseClaims(token, TOKEN_TYPE_ACCESS);
    Long memberId = Long.parseLong(claims.getSubject());
    MemberRole role = MemberRole.valueOf(claims.get(CLAIM_ROLE, String.class));
    return JwtAuthentication.of(memberId, role);
  }

  public Long parseRefresh(String token) {
    Claims claims = parseClaims(token, TOKEN_TYPE_REFRESH);
    return Long.parseLong(claims.getSubject());
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
