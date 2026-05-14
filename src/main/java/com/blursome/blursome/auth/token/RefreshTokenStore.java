package com.blursome.blursome.auth.token;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RefreshToken의 Redis 저장소.
 *
 * <p>키 스킴: {@code blursome:member:<memberId>:refresh-token}.
 * TTL은 Redis가 자동 만료하므로 서버 측 별도 클린업이 불필요하다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

  private static final String KEY_FORMAT = "blursome:member:%d:refresh-token";

  private final StringRedisTemplate redisTemplate;

  /** memberId에 RefreshToken을 TTL과 함께 저장한다(기존 값은 덮어씀 — 토큰 회전 시 활용). */
  public void save(Long memberId, String refreshToken, long ttlSeconds) {
    redisTemplate.opsForValue()
        .set(buildKey(memberId), refreshToken, Duration.ofSeconds(ttlSeconds));
  }

  /** memberId에 저장된 RefreshToken을 조회한다. */
  public Optional<String> find(Long memberId) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
  }

  /** memberId에 저장된 RefreshToken을 삭제한다(없어도 무오류). */
  public void delete(Long memberId) {
    redisTemplate.delete(buildKey(memberId));
  }

  private String buildKey(Long memberId) {
    return KEY_FORMAT.formatted(memberId);
  }
}
