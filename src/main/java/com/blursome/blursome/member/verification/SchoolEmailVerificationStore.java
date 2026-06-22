package com.blursome.blursome.member.verification;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 학교 이메일 인증 코드의 Redis 저장소.
 *
 * <p>키 스킴: {@code blursome:member:<memberId>:school-email-verification}. 값은
 * {@code <email>|<code>} 형태로, 코드뿐 아니라 발송 대상 이메일까지 묶어 저장해 검증 시 이메일
 * 일치 여부를 함께 확인한다. TTL은 Redis가 자동 만료하므로 별도 클린업이 불필요하다.
 */
@Component
@RequiredArgsConstructor
public class SchoolEmailVerificationStore {

  /** 인증 코드 유효 시간(단일 출처). 저장 TTL과 메일 본문 안내가 이 값을 공유한다. */
  public static final Duration CODE_TTL = Duration.ofMinutes(5);

  private static final String KEY_FORMAT = "blursome:member:%d:school-email-verification";
  private static final String VALUE_DELIMITER = "|";

  private final StringRedisTemplate redisTemplate;

  /** 회원의 인증 코드를 대상 이메일과 함께 TTL을 걸어 저장한다(재발송 시 기존 값 덮어씀). */
  public void save(Long memberId, String email, String code, Duration ttl) {
    redisTemplate.opsForValue().set(buildKey(memberId), email + VALUE_DELIMITER + code, ttl);
  }

  /** 저장된 인증 정보를 조회한다(없으면 만료/미발송). */
  public Optional<VerificationEntry> find(Long memberId) {
    String raw = redisTemplate.opsForValue().get(buildKey(memberId));
    if (raw == null) {
      return Optional.empty();
    }
    int delimiter = raw.indexOf(VALUE_DELIMITER);
    if (delimiter < 0) {
      return Optional.empty();
    }
    return Optional.of(
        new VerificationEntry(raw.substring(0, delimiter), raw.substring(delimiter + 1)));
  }

  /** 인증 성공·만료 처리 후 코드를 삭제한다(없어도 무오류). */
  public void delete(Long memberId) {
    redisTemplate.delete(buildKey(memberId));
  }

  private String buildKey(Long memberId) {
    return KEY_FORMAT.formatted(memberId);
  }

  /** 저장된 인증 정보(대상 이메일 + 코드). */
  public record VerificationEntry(String email, String code) {

  }
}
