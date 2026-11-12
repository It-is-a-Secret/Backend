package com.blursome.blursome.global.secretmoa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * SecretMoa S3 자격증명 공급자의 캐시·갱신 규칙을 검증한다. 시간은 테스트 clock으로 움직인다.
 * 기본 설정: TTL 60분, prefetch 15분 전, stale 8분 전, min-ttl 30분.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretMoaS3CredentialsProviderTest {

  private static final String BUCKET = "test-originals";
  private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(60);
  private static final SecretMoaProperties.S3 SETTINGS = new SecretMoaProperties.S3(true,
      Duration.ofMinutes(30), Duration.ofMinutes(15), Duration.ofMinutes(8),
      new SecretMoaProperties.Target(UUID.randomUUID(), UUID.randomUUID()),
      new SecretMoaProperties.Target(UUID.randomUUID(), UUID.randomUUID()));

  private final MutableClock clock = new MutableClock(T0);
  private final AtomicInteger issued = new AtomicInteger();
  private final List<SecretMoaS3CredentialsProvider> providers = new ArrayList<>();

  @AfterEach
  void closeProviders() {
    providers.forEach(SecretMoaS3CredentialsProvider::close);
  }

  /** 호출 시각 기준 TTL 60분짜리 자격증명. 발급 순번이 키에 들어간다. */
  private S3Lease lease(Duration ttl, String bucket, boolean writable) {
    int n = issued.incrementAndGet();
    return new S3Lease(UUID.randomUUID(), "ASIA-KEY-" + n, "SECRET-" + n, "TOKEN-" + n,
        clock.instant().plus(ttl), ttl, bucket, "", writable);
  }

  private SecretMoaS3CredentialsProvider provider(Supplier<S3Lease> issuer, boolean requireWritable) {
    SecretMoaS3CredentialsProvider p =
        new SecretMoaS3CredentialsProvider("originals", issuer, BUCKET, requireWritable, SETTINGS, clock);
    providers.add(p);
    return p;
  }

  private SecretMoaS3CredentialsProvider normal() {
    return provider(() -> lease(TTL, BUCKET, true), true);
  }

  private static String key(AwsCredentials c) {
    return c.accessKeyId();
  }

  @Test
  @DisplayName("첫 호출에 한 번 발급하고 세션 자격증명으로 돌려준 뒤 캐시한다")
  void firstCall_issuesOnce_andCaches() {
    SecretMoaS3CredentialsProvider p = normal();

    AwsCredentials first = p.resolveCredentials();
    AwsCredentials second = p.resolveCredentials();

    assertThat(issued).hasValue(1);
    assertThat(first).isInstanceOf(AwsSessionCredentials.class);
    assertThat(((AwsSessionCredentials) first).sessionToken()).isEqualTo("TOKEN-1");
    assertThat(second).isSameAs(first);
  }

  @Test
  @DisplayName("prefetch 구간 전에는 갱신하지 않는다")
  void beforePrefetch_noRefresh() {
    SecretMoaS3CredentialsProvider p = normal();
    p.resolveCredentials();

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(16)));

    assertThat(key(p.resolveCredentials())).isEqualTo("ASIA-KEY-1");
    assertThat(issued).hasValue(1);
  }

  @Test
  @DisplayName("prefetch 구간(stale 전)에 들어가면 새 자격증명으로 갱신한다")
  void prefetchWindow_refreshes() {
    SecretMoaS3CredentialsProvider p = normal();
    p.resolveCredentials();

    // SDK가 prefetch 시각에 최대 5분 지터를 주므로 stale 1분 전(만료 9분 전)으로 이동한다.
    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(9)));

    assertThat(key(p.resolveCredentials())).isEqualTo("ASIA-KEY-2");
    assertThat(issued).hasValue(2);
  }

  @Test
  @DisplayName("prefetch 구간에서 갱신이 실패하면 기존 자격증명을 계속 쓴다")
  void prefetchFailure_keepsCurrent() {
    AtomicInteger calls = new AtomicInteger();
    SecretMoaS3CredentialsProvider p = provider(() -> {
      if (calls.incrementAndGet() > 1) {
        throw SecretMoaException.fromStatus(503, "issuance_unavailable", null);
      }
      return lease(TTL, BUCKET, true);
    }, true);
    p.resolveCredentials();

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(9)));

    assertThat(key(p.resolveCredentials())).isEqualTo("ASIA-KEY-1");
    assertThat(calls.get()).isGreaterThan(1);
  }

  @Test
  @DisplayName("stale 구간(만료 8분 이내)에서는 기존 값을 쓰지 않고, 갱신이 실패하면 예외를 던진다")
  void staleWindow_failureThrows() {
    AtomicInteger calls = new AtomicInteger();
    SecretMoaS3CredentialsProvider p = provider(() -> {
      if (calls.incrementAndGet() > 1) {
        throw SecretMoaException.fromStatus(503, "issuance_unavailable", null);
      }
      return lease(TTL, BUCKET, true);
    }, true);
    p.resolveCredentials();

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(7)));

    assertThatThrownBy(p::resolveCredentials).isInstanceOf(SecretMoaException.class);
  }

  @Test
  @DisplayName("stale 구간에서 갱신에 성공하면 새 자격증명을 쓴다 (만료 직전 값으로 presign하지 않음)")
  void staleWindow_successUsesNew() {
    SecretMoaS3CredentialsProvider p = normal();
    p.resolveCredentials();

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(7)));

    assertThat(key(p.resolveCredentials())).isEqualTo("ASIA-KEY-2");
  }

  @Test
  @DisplayName("여러 스레드가 동시에 처음 호출해도 발급은 한 번만 한다")
  void concurrentFirstCalls_issueOnce() throws Exception {
    SecretMoaS3CredentialsProvider p = provider(() -> {
      sleep(200);
      return lease(TTL, BUCKET, true);
    }, true);
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<AwsCredentials>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(pool.submit(() -> {
          start.await();
          return p.resolveCredentials();
        }));
      }
      start.countDown();
      for (Future<AwsCredentials> r : results) {
        assertThat(key(r.get(10, TimeUnit.SECONDS))).isEqualTo("ASIA-KEY-1");
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(issued).hasValue(1);
  }

  @Test
  @DisplayName("남은 수명이 min-ttl(30분)보다 짧으면 거부한다")
  void shortTtl_rejected() {
    SecretMoaS3CredentialsProvider p = provider(() -> lease(Duration.ofMinutes(15), BUCKET, true), true);

    assertThatThrownBy(p::resolveCredentials)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("min-ttl");
  }

  @Test
  @DisplayName("기대한 버킷이 아닌 자격증명은 거부한다 (Target ID를 서로 바꿔 넣은 경우)")
  void wrongBucket_rejected() {
    SecretMoaS3CredentialsProvider p = provider(() -> lease(TTL, "test-variants", true), true);

    assertThatThrownBy(p::resolveCredentials)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("test-variants")
        .hasMessageContaining(BUCKET);
  }

  @Test
  @DisplayName("쓰기가 필요한데 읽기 전용 Role이면 거부한다")
  void readOnly_rejectedWhenWritableRequired() {
    SecretMoaS3CredentialsProvider p = provider(() -> lease(TTL, BUCKET, false), true);

    assertThatThrownBy(p::resolveCredentials)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable");
  }

  @Test
  @DisplayName("읽기 전용이 허용되면 읽기 전용 Role을 받아들인다")
  void readOnly_acceptedWhenAllowed() {
    SecretMoaS3CredentialsProvider p = provider(() -> lease(TTL, BUCKET, false), false);

    assertThat(key(p.resolveCredentials())).isEqualTo("ASIA-KEY-1");
  }

  @Test
  @DisplayName("갱신 로그와 toString에 키·토큰이 남지 않는다")
  void noSecretsInLogsOrToString(CapturedOutput output) {
    SecretMoaS3CredentialsProvider p = normal();
    p.resolveCredentials();

    assertThat(output.getAll()).contains("SecretMoa S3 credentials refreshed")
        .doesNotContain("ASIA-KEY-1", "SECRET-1", "TOKEN-1");
    assertThat(p.toString()).doesNotContain("ASIA-KEY-1", "SECRET-1", "TOKEN-1");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 테스트에서 시각을 직접 옮기는 clock. */
  static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      this.now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
