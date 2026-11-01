package com.blursome.blursome.global.secretmoa.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.secretmoa.DbLease;
import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaException;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import com.blursome.blursome.global.secretmoa.SecretMoaTestErrors;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 로테이터 상태 머신을 가짜 clock·가짜 SecretMoa·가짜 커넥션으로 검증한다.
 * 기본 타이밍: TTL 60분, renew-before 20분, drain-alarm-before 3분, check-interval 60초.
 */
@ExtendWith(OutputCaptureExtension.class)
class DbLeaseRotatorTest {

  private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(60);
  private static final UUID DB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ROLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final SecretMoaProperties.Db SETTINGS = new SecretMoaProperties.Db(true, DB_ID, ROLE_ID,
      Duration.ofMinutes(20), Duration.ofMinutes(3), Duration.ofSeconds(60));

  private final MutableClock clock = new MutableClock(T0);
  private final SecretMoaClient client = mock(SecretMoaClient.class);
  private final HikariDataSource pool = mock(HikariDataSource.class);
  private final HikariPoolMXBean poolBean = mock(HikariPoolMXBean.class);
  private final AtomicInteger issued = new AtomicInteger();
  private final Set<String> rejectedUsers = ConcurrentHashMap.newKeySet();
  private final ConnectionFactory connections = (u, p) -> {
    if (rejectedUsers.contains(u)) {
      throw new SQLException("Access denied for user", "28000", 1045);
    }
    return LeaseAwareDataSourceTest.fakeConnection(new AtomicInteger());
  };
  private DbLeaseRotator rotator;

  @BeforeEach
  void setUp() {
    willAnswer(inv -> nextLease(TTL)).given(client).issueDbCredential(DB_ID, ROLE_ID);
    given(pool.getHikariPoolMXBean()).willReturn(poolBean);
    rotator = new DbLeaseRotator(client, SETTINGS, clock, connections);
  }

  private DbLease nextLease(Duration ttl) {
    int n = issued.incrementAndGet();
    return new DbLease(new UUID(0, n), "sm_user" + n, "pw-secret-" + n, clock.instant().plus(ttl), ttl);
  }

  private static UUID leaseId(int n) {
    return new UUID(0, n);
  }

  private LeaseAwareDataSource started() {
    LeaseAwareDataSource ds = rotator.start();
    rotator.attach(pool);
    return ds;
  }

  /** g1 발급 시각 기준 갱신 시점(만료 20분 전)으로 옮긴다. */
  private void moveToRenewal() {
    clock.set(T0.plus(TTL).minus(SETTINGS.renewBefore()));
  }

  @Test
  @DisplayName("기동: 첫 Lease를 발급·접속 검증하고 1세대로 시작한다")
  void start_issuesAndVerifies() {
    LeaseAwareDataSource ds = started();

    assertThat(ds.current().number()).isEqualTo(1);
    assertThat(ds.current().leaseId()).isEqualTo(leaseId(1));
    verify(client, never()).revokeDbLease(any());
  }

  @Test
  @DisplayName("기동: 첫 Lease TTL이 renew-before 이하면 회수하고 기동을 멈춘다")
  void start_ttlTooShort_revokesAndFails() {
    willAnswer(inv -> nextLease(Duration.ofMinutes(20))).given(client).issueDbCredential(DB_ID, ROLE_ID);

    assertThatThrownBy(rotator::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("renew-before");
    verify(client).revokeDbLease(leaseId(1));
  }

  @Test
  @DisplayName("기동: 첫 Lease로 접속하지 못하면 회수하고 기동을 멈춘다")
  void start_verifyFails_revokesAndFails() {
    rejectedUsers.add("sm_user1");

    assertThatThrownBy(rotator::start).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SQLState=28000").hasMessageNotContaining("pw-secret");
    verify(client).revokeDbLease(leaseId(1));
  }

  @Test
  @DisplayName("기동: SecretMoa 발급 실패는 그대로 기동 실패다")
  void start_issueFails() {
    willThrow(SecretMoaTestErrors.unavailable()).given(client).issueDbCredential(DB_ID, ROLE_ID);

    assertThatThrownBy(rotator::start).isInstanceOf(SecretMoaException.class);
  }

  @Test
  @DisplayName("갱신 시점 전에는 발급하지 않는다")
  void beforeRenewal_noIssue() {
    started();
    clock.set(T0.plus(TTL).minus(SETTINGS.renewBefore()).minusSeconds(1));

    rotator.tick();

    assertThat(issued).hasValue(1);
    assertThat(rotator.drainingGeneration()).isNull();
  }

  @Test
  @DisplayName("갱신 시점: 새 Lease로 전환하고 idle 커넥션을 소프트 퇴출하며 DRAINING에 들어간다")
  void atRenewal_rotates() {
    LeaseAwareDataSource ds = started();
    moveToRenewal();

    rotator.tick();

    assertThat(ds.current().number()).isEqualTo(2);
    assertThat(ds.current().leaseId()).isEqualTo(leaseId(2));
    assertThat(rotator.drainingGeneration().number()).isEqualTo(1);
    verify(poolBean).softEvictConnections();
  }

  @Test
  @DisplayName("새 Lease로 접속하지 못하면 그 Lease를 회수하고 전환하지 않으며, 다음 주기에 다시 시도한다")
  void newLeaseVerifyFails_revokesAndRetries() {
    LeaseAwareDataSource ds = started();
    moveToRenewal();
    rejectedUsers.add("sm_user2");

    rotator.tick();

    assertThat(ds.current().number()).isEqualTo(1);
    verify(client).revokeDbLease(leaseId(2));
    verify(poolBean, never()).softEvictConnections();

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();

    assertThat(ds.current().leaseId()).isEqualTo(leaseId(3));
  }

  @Test
  @DisplayName("이전 세대 커넥션이 남아 있으면 회수하지 않고, 0이 되면 회수한다")
  void drain_revokesOnlyWhenZero() throws SQLException {
    LeaseAwareDataSource ds = started();
    Connection inUse = ds.getConnection();
    moveToRenewal();
    rotator.tick();

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    verify(client, never()).revokeDbLease(leaseId(1));

    inUse.close();
    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();

    verify(client).revokeDbLease(leaseId(1));
    assertThat(rotator.drainingGeneration()).isNull();
  }

  @Test
  @DisplayName("전환 후 첫 점검에 이전 세대 커넥션이 남아 있으면 소프트 퇴출을 한 번 더 한다 (이후 반복하지 않음)")
  void drain_reEvictsOnce() throws SQLException {
    LeaseAwareDataSource ds = started();
    ds.getConnection();
    moveToRenewal();
    rotator.tick();
    verify(poolBean, times(1)).softEvictConnections();

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();

    verify(poolBean, times(2)).softEvictConnections();
  }

  @Test
  @DisplayName("만료 drain-alarm-before 전까지 커넥션이 남으면 ERROR 경보를 한 번 내고 회수하지 않는다")
  void drainAlarm(CapturedOutput output) throws SQLException {
    LeaseAwareDataSource ds = started();
    ds.getConnection();
    moveToRenewal();
    rotator.tick();

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(2)));
    rotator.tick();
    clock.set(clock.instant().plusSeconds(30));
    rotator.tick();

    assertThat(output.getAll().split("still has 1 connections", -1)).hasSize(2);
    verify(client, never()).revokeDbLease(leaseId(1));
  }

  @Test
  @DisplayName("이전 Lease가 커넥션을 남긴 채 만료되면 회수하지 않고 SecretMoa Reaper에 맡긴다")
  void oldExpiredWithOpenConnections_leftToReaper(CapturedOutput output) throws SQLException {
    LeaseAwareDataSource ds = started();
    ds.getConnection();
    moveToRenewal();
    rotator.tick();

    clock.set(T0.plus(TTL));
    rotator.tick();

    assertThat(rotator.drainingGeneration()).isNull();
    assertThat(output.getAll()).contains("expired with 1 connections still open");
    verify(client, never()).revokeDbLease(leaseId(1));
  }

  @Test
  @DisplayName("이전 Lease 회수가 실패하면 다음 주기에 다시 회수한다")
  void drainRevokeFails_retries() {
    started();
    moveToRenewal();
    rotator.tick();
    org.mockito.Mockito.doThrow(SecretMoaTestErrors.unavailable())
        .doNothing().when(client).revokeDbLease(leaseId(1));

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    assertThat(rotator.drainingGeneration()).isNotNull();

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    assertThat(rotator.drainingGeneration()).isNull();
    verify(client, times(2)).revokeDbLease(leaseId(1));
  }

  @Test
  @DisplayName("발급 실패: WARN 후 다음 주기 재시도, 남은 수명이 renew-before 절반 미만이면 ERROR")
  void issueFailure_retriesAndEscalates(CapturedOutput output) {
    LeaseAwareDataSource ds = started();
    willThrow(SecretMoaTestErrors.unavailable()).given(client).issueDbCredential(DB_ID, ROLE_ID);

    moveToRenewal();
    rotator.tick();
    assertThat(output.getAll()).contains("renewal failed, retrying next check");

    clock.set(T0.plus(TTL).minus(Duration.ofMinutes(9)));
    rotator.tick();
    assertThat(output.getAll()).contains("renewal failing; current lease expires in 540s");
    assertThat(ds.current().number()).isEqualTo(1);

    willAnswer(inv -> nextLease(TTL)).given(client).issueDbCredential(DB_ID, ROLE_ID);
    rotator.tick();
    assertThat(ds.current().number()).isEqualTo(2);
  }

  @Test
  @DisplayName("주기 점검과 종료가 겹쳐도 Lease를 이중 발급하지 않고, 종료 후에는 점검이 아무것도 하지 않는다")
  void tickAndCloseConcurrently_noDoubleIssue() throws Exception {
    started();
    moveToRenewal();
    CountDownLatch issuing = new CountDownLatch(1);
    willAnswer(inv -> {
      issuing.countDown();
      Thread.sleep(300);
      return nextLease(TTL);
    }).given(client).issueDbCredential(DB_ID, ROLE_ID);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> tick = executor.submit(rotator::tick);
      assertThat(issuing.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> close = executor.submit(rotator::close);
      tick.get(5, TimeUnit.SECONDS);
      close.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    rotator.tick();
    rotator.close();

    assertThat(issued).hasValue(2);
    verify(client).revokeDbLease(leaseId(1));
    verify(client).revokeDbLease(leaseId(2));
  }

  @Test
  @DisplayName("종료: 풀을 먼저 닫은 뒤 현재·이전 세대를 회수한다")
  void close_poolThenRevoke() {
    started();
    moveToRenewal();
    rotator.tick();

    rotator.close();

    InOrder order = inOrder(pool, client);
    order.verify(pool).close();
    order.verify(client).revokeDbLease(leaseId(1));
    order.verify(client).revokeDbLease(leaseId(2));
  }

  @Test
  @DisplayName("Hikari max-lifetime은 30초 이상이고 renew-before보다 짧아야 한다 (R5)")
  void validatePool() {
    HikariConfig config = new HikariConfig();
    config.setMaxLifetime(Duration.ofMinutes(15).toMillis());
    DbLeaseRotator.validatePool(config, SETTINGS);

    config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
    assertThatThrownBy(() -> DbLeaseRotator.validatePool(config, SETTINGS))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-lifetime");

    HikariConfig tooShort = new HikariConfig();
    tooShort.setMaxLifetime(Duration.ofSeconds(10).toMillis());
    assertThatThrownBy(() -> DbLeaseRotator.validatePool(tooShort, SETTINGS))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 30s");
  }

  @Test
  @DisplayName("로그에 임시 DB 비밀번호가 남지 않는다")
  void noPasswordInLogs(CapturedOutput output) throws SQLException {
    LeaseAwareDataSource ds = started();
    ds.getConnection().close();
    moveToRenewal();
    rotator.tick();
    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    rotator.close();

    assertThat(output.getAll()).contains("SecretMoa DB lease rotated").doesNotContain("pw-secret");
    assertThat(ds.current().toString()).doesNotContain("pw-secret");
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
