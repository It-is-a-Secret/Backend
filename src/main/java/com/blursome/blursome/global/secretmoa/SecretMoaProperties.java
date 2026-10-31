package com.blursome.blursome.global.secretmoa;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SecretMoa 임시 자격증명 연동 설정. S3와 DB 기능은 각각 플래그로 켜며 기본은 모두 꺼져 있다.
 *
 * <p>켜진 기능에 필요한 API Key와 ID가 비어 있거나 타이밍 값의 관계가 맞지 않으면 바인딩 단계에서
 * 예외를 던져 기동을 멈춘다. 더미 값으로 조용히 뜨지 않게 하기 위함이다. 실제 Lease TTL이 필요한
 * 검증(첫 Lease TTL, S3 최소 TTL)은 각 기능이 첫 발급 직후 수행한다.
 *
 * @param url SecretMoa Core 주소
 * @param apiKey Application API Key ({@code smk_...}). 켜진 기능이 있으면 필수
 * @param instanceId 감사 로그용 인스턴스 식별자. SecretMoa 형식({@code [A-Za-z0-9_-]{1,64}})으로 정규화한다
 * @param s3 S3 임시 자격증명 설정
 * @param db DB 임시 계정 설정
 */
@ConfigurationProperties(prefix = "app.secretmoa")
public record SecretMoaProperties(
    @DefaultValue("http://127.0.0.1:8081") URI url,
    String apiKey,
    String instanceId,
    @DefaultValue S3 s3,
    @DefaultValue Db db
) {

  private static final Pattern INSTANCE_ID_INVALID = Pattern.compile("[^A-Za-z0-9_-]");
  private static final int INSTANCE_ID_MAX_LENGTH = 64;

  public SecretMoaProperties {
    instanceId = normalizeInstanceId(instanceId);
    if (s3.enabled() || db.enabled()) {
      if (url == null || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
          || url.getHost() == null) {
        throw new IllegalArgumentException("app.secretmoa.url must be an http(s) URL");
      }
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("app.secretmoa.api-key is required when a SecretMoa feature is enabled");
      }
    }
  }

  /** S3나 DB 중 하나라도 켜져 있으면 true. */
  public boolean anyEnabled() {
    return s3.enabled() || db.enabled();
  }

  /** API Key가 로그나 디버거 출력에 남지 않도록 가린다. */
  @Override
  public String toString() {
    return "SecretMoaProperties[url=" + url + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "" : "***")
        + ", instanceId=" + instanceId + ", s3=" + s3 + ", db=" + db + "]";
  }

  /**
   * SecretMoa의 {@code X-Instance-Id} 형식에 맞춘다. 기본값은 {@code ${ACTIVE}-${HOSTNAME}}이라
   * {@code local,maillog} 같은 쉼표가 들어올 수 있으므로 허용되지 않는 문자는 {@code -}로 바꾼다.
   */
  static String normalizeInstanceId(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String normalized = INSTANCE_ID_INVALID.matcher(raw.strip()).replaceAll("-");
    return normalized.length() > INSTANCE_ID_MAX_LENGTH
        ? normalized.substring(0, INSTANCE_ID_MAX_LENGTH)
        : normalized;
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * S3 임시 자격증명 설정. 원본과 블러본 버킷은 SecretMoa에서 서로 다른 Target·Role이다.
   *
   * @param enabled 켜면 S3 요청에 SecretMoa 자격증명을 쓴다
   * @param minTtl 이보다 짧은 S3 Lease면 기동 실패 (Presigned URL 수명 보장)
   * @param prefetchBefore 만료 이 시간 전부터 백그라운드 갱신을 시도한다
   * @param staleBefore 만료 이 시간 전부터는 갱신에 성공해야 한다 (presign 5분 + 시계 오차)
   * @param originals 원본 버킷 Target·Role
   * @param variants 블러본 버킷 Target·Role
   */
  public record S3(
      boolean enabled,
      @DefaultValue("30m") Duration minTtl,
      @DefaultValue("15m") Duration prefetchBefore,
      @DefaultValue("8m") Duration staleBefore,
      @DefaultValue Target originals,
      @DefaultValue Target variants
  ) {

    public S3 {
      if (enabled) {
        originals.requireIds("app.secretmoa.s3.originals");
        variants.requireIds("app.secretmoa.s3.variants");
        requirePositive(minTtl, "app.secretmoa.s3.min-ttl");
        requirePositive(prefetchBefore, "app.secretmoa.s3.prefetch-before");
        requirePositive(staleBefore, "app.secretmoa.s3.stale-before");
        if (staleBefore.compareTo(prefetchBefore) >= 0) {
          throw new IllegalArgumentException("app.secretmoa.s3.stale-before must be shorter than prefetch-before");
        }
        if (prefetchBefore.compareTo(minTtl) >= 0) {
          throw new IllegalArgumentException("app.secretmoa.s3.prefetch-before must be shorter than min-ttl");
        }
      }
    }
  }

  /**
   * SecretMoa S3 Target과 그 안의 Role.
   *
   * @param targetId S3 Target ID
   * @param roleId S3 Credential Role ID
   */
  public record Target(UUID targetId, UUID roleId) {

    void requireIds(String name) {
      if (targetId == null || roleId == null) {
        throw new IllegalArgumentException(name + ".target-id and role-id are required when S3 is enabled");
      }
    }
  }

  /**
   * DB 임시 계정 설정.
   *
   * @param enabled 켜면 DataSource가 SecretMoa 임시 계정을 쓴다
   * @param databaseId SecretMoa Database ID
   * @param roleId SecretMoa Credential Role ID
   * @param renewBefore 만료 이 시간 전에 새 Lease로 전환한다
   * @param drainAlarmBefore 이전 Lease 커넥션이 만료 이 시간 전까지 남아 있으면 경보한다
   * @param checkInterval 로테이터 점검 주기
   */
  public record Db(
      boolean enabled,
      UUID databaseId,
      UUID roleId,
      @DefaultValue("20m") Duration renewBefore,
      @DefaultValue("3m") Duration drainAlarmBefore,
      @DefaultValue("60s") Duration checkInterval
  ) {

    public Db {
      if (enabled) {
        if (databaseId == null || roleId == null) {
          throw new IllegalArgumentException(
              "app.secretmoa.db.database-id and role-id are required when DB is enabled");
        }
        requirePositive(renewBefore, "app.secretmoa.db.renew-before");
        requirePositive(drainAlarmBefore, "app.secretmoa.db.drain-alarm-before");
        requirePositive(checkInterval, "app.secretmoa.db.check-interval");
        if (drainAlarmBefore.compareTo(renewBefore) >= 0) {
          throw new IllegalArgumentException("app.secretmoa.db.drain-alarm-before must be shorter than renew-before");
        }
        if (checkInterval.multipliedBy(3).compareTo(renewBefore) > 0) {
          throw new IllegalArgumentException("app.secretmoa.db.check-interval must be at most renew-before / 3");
        }
      }
    }
  }
}
