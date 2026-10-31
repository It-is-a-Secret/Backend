package com.blursome.blursome.global.secretmoa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.awssdk.utils.cache.CachedSupplier;
import software.amazon.awssdk.utils.cache.RefreshResult;

/**
 * SecretMoa S3 임시 자격증명을 캐시하고 만료 전에 갱신하는 AWS 자격증명 공급자. 버킷 하나에 하나씩 둔다.
 *
 * <ul>
 *   <li>만료 {@code prefetchBefore} 전부터: 호출한 스레드 하나가 갱신한다. 실패하면 기존 값을 계속 쓴다.</li>
 *   <li>만료 {@code staleBefore} 전부터: 기존 값을 쓰지 않는다. 갱신에 실패하면 예외를 던진다.
 *       Presigned URL(5분)이 자격증명보다 먼저 무효가 되지 않게 하기 위함이다.</li>
 *   <li>동시에 여러 스레드가 호출해도 발급은 한 번만 한다.</li>
 * </ul>
 *
 * <p>발급받은 자격증명의 버킷이 기대한 버킷과 다르거나, 쓰기가 필요한데 읽기 전용이거나, 남은 수명이
 * {@code minTtl}보다 짧으면 사용하지 않고 예외를 던진다. 설정 ID를 잘못 넣은 경우를 기동 시 잡기 위함이다.
 */
public final class SecretMoaS3CredentialsProvider implements AwsCredentialsProvider, SdkAutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SecretMoaS3CredentialsProvider.class);

  private final String name;
  private final Supplier<S3Lease> issuer;
  private final String expectedBucket;
  private final boolean requireWritable;
  private final Duration minTtl;
  private final Duration prefetchBefore;
  private final Duration staleBefore;
  private final Clock clock;
  private final CachedSupplier<AwsCredentials> cache;

  /**
   * @param name 로그용 이름 (예: {@code originals})
   * @param issuer SecretMoa 발급 호출
   * @param expectedBucket 자격증명이 가리켜야 하는 버킷
   * @param requireWritable 쓰기 권한이 필요하면 true
   * @param settings S3 타이밍 설정
   * @param clock 현재 시각
   */
  public SecretMoaS3CredentialsProvider(String name, Supplier<S3Lease> issuer, String expectedBucket,
      boolean requireWritable, SecretMoaProperties.S3 settings, Clock clock) {
    this.name = name;
    this.issuer = issuer;
    this.expectedBucket = expectedBucket;
    this.requireWritable = requireWritable;
    this.minTtl = settings.minTtl();
    this.prefetchBefore = settings.prefetchBefore();
    this.staleBefore = settings.staleBefore();
    this.clock = clock;
    this.cache = CachedSupplier.builder(this::refresh)
        .cachedValueName("SecretMoaS3Credentials-" + name)
        .clock(clock)
        .build();
  }

  @Override
  public AwsCredentials resolveCredentials() {
    return cache.get();
  }

  @Override
  public void close() {
    cache.close();
  }

  private RefreshResult<AwsCredentials> refresh() {
    S3Lease lease = issuer.get();
    if (!expectedBucket.equals(lease.bucket())) {
      throw new IllegalStateException("SecretMoa S3 credential for " + name + " is for bucket " + lease.bucket()
          + ", expected " + expectedBucket + ". Check the S3 target/role IDs");
    }
    if (requireWritable && !lease.writable()) {
      throw new IllegalStateException("SecretMoa S3 role for " + name + " must be writable");
    }
    Instant expiresAt = lease.expiresAt();
    Duration remaining = Duration.between(clock.instant(), expiresAt);
    if (remaining.compareTo(minTtl) < 0) {
      throw new IllegalStateException("SecretMoa S3 credential for " + name + " lives " + remaining.toSeconds()
          + "s, shorter than min-ttl " + minTtl.toSeconds() + "s. Raise the role TTL");
    }
    log.info("SecretMoa S3 credentials refreshed name={} leaseId={} expiresAt={}", name, lease.leaseId(), expiresAt);
    AwsCredentials credentials = AwsSessionCredentials.builder()
        .accessKeyId(lease.accessKeyId())
        .secretAccessKey(lease.secretAccessKey())
        .sessionToken(lease.sessionToken())
        .expirationTime(expiresAt)
        .build();
    return RefreshResult.builder(credentials)
        .prefetchTime(expiresAt.minus(prefetchBefore))
        .staleTime(expiresAt.minus(staleBefore))
        .build();
  }

  @Override
  public String toString() {
    return "SecretMoaS3CredentialsProvider[" + name + ", bucket=" + expectedBucket + "]";
  }
}
