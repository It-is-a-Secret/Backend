package com.blursome.blursome.auth.token;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

  private static final String KEY_FORMAT = "blursome:member:%d:refresh-token";

  private final StringRedisTemplate redisTemplate;

  public void save(Long memberId, String refreshToken, long ttlSeconds) {
    redisTemplate.opsForValue()
        .set(buildKey(memberId), refreshToken, Duration.ofSeconds(ttlSeconds));
  }

  public Optional<String> find(Long memberId) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
  }

  public void delete(Long memberId) {
    redisTemplate.delete(buildKey(memberId));
  }

  private String buildKey(Long memberId) {
    return KEY_FORMAT.formatted(memberId);
  }
}
