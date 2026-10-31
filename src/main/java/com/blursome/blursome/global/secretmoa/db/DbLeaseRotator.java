package com.blursome.blursome.global.secretmoa.db;

import com.blursome.blursome.global.secretmoa.DbLease;
import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaException;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SecretMoa DB 임시 계정을 만료 전에 새 계정으로 바꾼다. 전용 단일 스레드가 {@code check-interval}마다
 * {@link #tick()}을 실행하고, 수동 호출·종료와는 {@link ReentrantLock}으로 직렬화한다 (R16).
 *
 * <pre>
 * ACTIVE(g1)
 *   └─ now ≥ g1.expiresAt − renew-before
 *        1. g2 발급
 *        2. g2로 직접 접속해 SELECT 1 → 실패하면 g2 회수 후 다음 주기 재시도
 *        3. old = dataSource.switchTo(g2)
 *        4. pool.softEvictConnections()   // idle g1 즉시 종료, 사용 중 g1은 반납 시 종료
 * DRAINING(g1 → g2)
 *   ├─ g1.inFlightAndOpen == 0        → g1 회수 → ACTIVE(g2)
 *   ├─ now ≥ g1.expiresAt − drain-alarm-before → 경보 (회수하지 않음)
 *   └─ g1 만료                         → SecretMoa Reaper가 정리
 * 발급 실패 → 다음 주기 재시도. 남은 수명이 renew-before의 절반 미만이면 ERROR
 * 종료 → 스케줄 중단 → pool.close() → 현재·이전 세대 회수
 * </pre>
 *
 * <p>인스턴스마다 자기 Lease를 가지므로 분산 락은 필요 없다.
 */
public class DbLeaseRotator implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DbLeaseRotator.class);

  static final Duration MIN_HIKARI_MAX_LIFETIME = Duration.ofSeconds(30);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(10);
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(15);

  private final SecretMoaClient client;
  private final SecretMoaProperties.Db settings;
  private final Clock clock;
  private final ConnectionFactory connectionFactory;
  private final ReentrantLock lock = new ReentrantLock();

  private LeaseAwareDataSource dataSource;
  private HikariDataSource pool;
  private ScheduledExecutorService scheduler;
  private long generationNumber;
  private Generation draining;
  private Instant drainStartedAt;
  private boolean drainAlarmed;
  private boolean reEvicted;
  private boolean closed;
  private Instant lastHeartbeat = Instant.MIN;

  public DbLeaseRotator(SecretMoaClient client, SecretMoaProperties.Db settings, Clock clock,
      ConnectionFactory connectionFactory) {
    this.client = client;
    this.settings = settings;
    this.clock = clock;
    this.connectionFactory = connectionFactory;
  }

  /**
   * Hikari 설정이 로테이션과 맞는지 확인한다 (R5). 커넥션이 갱신 전에 자연 교체되어야 이전 계정을 회수할 수
   * 있으므로 {@code max-lifetime < renew-before}여야 한다. Hikari는 30초 미만 값을 기본값(30분)으로 조용히
   * 바꾸므로 30초 이상인지도 확인한다.
   */
  public static void validatePool(HikariConfig config, SecretMoaProperties.Db settings) {
    Duration maxLifetime = Duration.ofMillis(config.getMaxLifetime());
    if (maxLifetime.compareTo(MIN_HIKARI_MAX_LIFETIME) < 0 || maxLifetime.compareTo(settings.renewBefore()) >= 0) {
      throw new IllegalStateException("spring.datasource.hikari.max-lifetime must be at least "
          + MIN_HIKARI_MAX_LIFETIME.toSeconds() + "s and shorter than app.secretmoa.db.renew-before ("
          + settings.renewBefore().toSeconds() + "s) when SecretMoa DB is enabled. Current: "
          + maxLifetime.toSeconds() + "s");
    }
  }

  /** 첫 Lease를 발급·검증하고 DataSource를 만든다. 실패하면 발급한 Lease를 회수하고 기동을 멈춘다. */
  public LeaseAwareDataSource start() {
    lock.lock();
    try {
      if (dataSource != null) {
        throw new IllegalStateException("DbLeaseRotator already started");
      }
      DbLease lease = client.issueDbCredential(settings.databaseId(), settings.roleId());
      try {
        requireLongerThanRenewBefore(lease);
        verify(lease);
      } catch (RuntimeException e) {
        revokeQuietly(lease.leaseId(), "startup check failed");
        throw e;
      }
      dataSource = new LeaseAwareDataSource(connectionFactory, new Generation(++generationNumber, lease));
      log.info("SecretMoa DB lease active generation={} leaseId={} expiresAt={}",
          generationNumber, lease.leaseId(), lease.expiresAt());
      return dataSource;
    } finally {
      lock.unlock();
    }
  }

  /** 소프트 퇴출과 상태 로그에 쓸 Hikari 풀을 연결한다. */
  public void attach(HikariDataSource pool) {
    lock.lock();
    try {
      this.pool = pool;
    } finally {
      lock.unlock();
    }
  }

  /** 전용 스레드에서 주기 점검을 시작한다. */
  public void startSchedule() {
    lock.lock();
    try {
      if (scheduler != null || closed) {
        return;
      }
      scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "secretmoa-db-rotator");
        thread.setDaemon(true);
        return thread;
      });
      long interval = settings.checkInterval().toMillis();
      scheduler.scheduleWithFixedDelay(this::tickSafely, interval, interval, TimeUnit.MILLISECONDS);
    } finally {
      lock.unlock();
    }
  }

  private void tickSafely() {
    try {
      tick();
    } catch (RuntimeException | Error e) {
      // 예외가 새면 스케줄이 멈춘다. 기록만 하고 다음 주기에 다시 점검한다.
      log.error("SecretMoa DB rotator check failed", e);
    }
  }

  /** 한 번 점검한다. 주기 실행과 수동 호출·종료가 겹쳐도 락으로 직렬화된다. */
  void tick() {
    lock.lock();
    try {
      if (closed || dataSource == null) {
        return;
      }
      Instant now = clock.instant();
      if (draining != null) {
        checkDrain(now);
      }
      Generation current = dataSource.current();
      // 이전 세대는 전환 후 renew-before 안에 만료되고, 현재 세대의 갱신 시점은 그보다 늦다.
      // 그래서 DRAINING 중에는 갱신하지 않아도 현재 세대가 만료 전에 갱신된다.
      if (draining == null && !now.isBefore(current.expiresAt().minus(settings.renewBefore()))) {
        rotate(now, current);
      }
      heartbeat(now);
    } finally {
      lock.unlock();
    }
  }

  private void checkDrain(Instant now) {
    Generation old = draining;
    int open = old.inFlightAndOpen();
    if (open == 0) {
      try {
        client.revokeDbLease(old.leaseId());
        log.info("SecretMoa DB lease drained and revoked generation={} leaseId={} drainSeconds={}",
            old.number(), old.leaseId(), Duration.between(drainStartedAt, now).toSeconds());
        draining = null;
      } catch (SecretMoaException e) {
        if (!now.isBefore(old.expiresAt())) {
          log.warn("SecretMoa DB lease revoke failed after expiry; SecretMoa reaper will remove it generation={} leaseId={}",
              old.number(), old.leaseId());
          draining = null;
        } else {
          log.warn("SecretMoa DB lease revoke failed, retrying next check generation={} leaseId={} reason={}",
              old.number(), old.leaseId(), e.reason());
        }
      }
      return;
    }
    if (!reEvicted) {
      // 전환 직전에 시작된 풀 채우기는 이전 계정 커넥션을 전환 "후"에 풀에 넣을 수 있다. 첫 소프트 퇴출이
      // 놓친 그 idle 커넥션을 한 번 더 정리해, max-lifetime까지 기다리지 않고 회수하게 한다.
      reEvicted = true;
      HikariPoolMXBean poolBean = pool == null ? null : pool.getHikariPoolMXBean();
      if (poolBean != null) {
        poolBean.softEvictConnections();
      }
    }
    if (!now.isBefore(old.expiresAt())) {
      log.error("SecretMoa DB lease expired with {} connections still open; SecretMoa reaper ends them "
          + "generation={} leaseId={}", open, old.number(), old.leaseId());
      draining = null;
      return;
    }
    if (!drainAlarmed && !now.isBefore(old.expiresAt().minus(settings.drainAlarmBefore()))) {
      drainAlarmed = true;
      log.error("SecretMoa DB lease still has {} connections {}s before expiry; long transactions will be cut "
          + "generation={} leaseId={} {}", open, Duration.between(now, old.expiresAt()).toSeconds(),
          old.number(), old.leaseId(), poolStats());
      return;
    }
    log.info("SecretMoa DB lease draining generation={} open={} drainSeconds={} {}", old.number(), open,
        Duration.between(drainStartedAt, now).toSeconds(), poolStats());
  }

  private void rotate(Instant now, Generation current) {
    DbLease next;
    try {
      next = client.issueDbCredential(settings.databaseId(), settings.roleId());
    } catch (SecretMoaException e) {
      Duration remaining = Duration.between(now, current.expiresAt());
      if (remaining.compareTo(settings.renewBefore().dividedBy(2)) < 0) {
        log.error("SecretMoa DB lease renewal failing; current lease expires in {}s generation={} reason={}",
            remaining.toSeconds(), current.number(), e.reason());
      } else {
        log.warn("SecretMoa DB lease renewal failed, retrying next check; current lease expires in {}s reason={}",
            remaining.toSeconds(), e.reason());
      }
      return;
    }
    try {
      requireLongerThanRenewBefore(next);
      verify(next);
    } catch (RuntimeException e) {
      log.warn("SecretMoa DB new lease rejected, retrying next check leaseId={} cause={}", next.leaseId(),
          e.getMessage());
      revokeQuietly(next.leaseId(), "rejected new lease");
      return;
    }
    Generation nextGeneration = new Generation(++generationNumber, next);
    Generation old = dataSource.switchTo(nextGeneration);
    HikariPoolMXBean poolBean = pool == null ? null : pool.getHikariPoolMXBean();
    if (poolBean != null) {
      poolBean.softEvictConnections();
    }
    draining = old;
    drainStartedAt = now;
    drainAlarmed = false;
    reEvicted = false;
    log.info("SecretMoa DB lease rotated generation={}->{} oldLeaseId={} newLeaseId={} newExpiresAt={} oldOpen={}",
        old.number(), nextGeneration.number(), old.leaseId(), next.leaseId(), next.expiresAt(), old.inFlightAndOpen());
  }

  private void heartbeat(Instant now) {
    if (Duration.between(lastHeartbeat, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
      log.debug("SecretMoa DB lease check generation={} {}", dataSource.current().number(), poolStats());
      return;
    }
    lastHeartbeat = now;
    Generation current = dataSource.current();
    log.info("SecretMoa DB lease generation={} leaseId={} remainingSeconds={} draining={} {}", current.number(),
        current.leaseId(), Duration.between(now, current.expiresAt()).toSeconds(),
        draining == null ? "none" : draining.number(), poolStats());
  }

  private String poolStats() {
    HikariPoolMXBean bean = pool == null ? null : pool.getHikariPoolMXBean();
    if (bean == null) {
      return "pool=n/a";
    }
    return "pool[active=" + bean.getActiveConnections() + ", idle=" + bean.getIdleConnections() + ", pending="
        + bean.getThreadsAwaitingConnection() + "]";
  }

  private void requireLongerThanRenewBefore(DbLease lease) {
    Duration remaining = Duration.between(clock.instant(), lease.expiresAt());
    if (remaining.compareTo(settings.renewBefore()) <= 0) {
      throw new IllegalStateException("SecretMoa DB lease lives " + remaining.toSeconds()
          + "s, which must be longer than app.secretmoa.db.renew-before (" + settings.renewBefore().toSeconds()
          + "s). Raise the role TTL or lower renew-before");
    }
  }

  /** 새 계정으로 직접 접속해 SELECT 1. 드라이버 메시지는 남기지 않고 SQLState·코드만 남긴다. */
  private void verify(DbLease lease) {
    try (Connection connection = connectionFactory.connect(lease.username(), lease.password());
        Statement statement = connection.createStatement()) {
      statement.execute("SELECT 1");
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot connect with SecretMoa DB lease " + lease.leaseId() + " (SQLState="
          + e.getSQLState() + ", errorCode=" + e.getErrorCode() + ")");
    }
  }

  private void revokeQuietly(UUID leaseId, String why) {
    try {
      client.revokeDbLease(leaseId);
    } catch (SecretMoaException e) {
      log.warn("SecretMoa DB lease revoke failed ({}); SecretMoa reaper will remove it at expiry leaseId={} reason={}",
          why, leaseId, e.reason());
    }
  }

  /** 종료: 스케줄 중단 → 풀 종료 → 현재·이전 세대 회수. 진행 중인 점검이 끝난 뒤에 실행된다. */
  @Override
  public void close() {
    ScheduledExecutorService toStop;
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      toStop = scheduler;
    } finally {
      lock.unlock();
    }
    if (toStop != null) {
      toStop.shutdown();
      try {
        if (!toStop.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
          toStop.shutdownNow();
        }
      } catch (InterruptedException e) {
        toStop.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    if (pool != null) {
      pool.close();
    }
    if (draining != null) {
      revokeQuietly(draining.leaseId(), "shutdown");
      draining = null;
    }
    if (dataSource != null) {
      Generation current = dataSource.current();
      revokeQuietly(current.leaseId(), "shutdown");
      log.info("SecretMoa DB leases revoked on shutdown lastGeneration={}", current.number());
    }
  }

  Generation currentGeneration() {
    return dataSource == null ? null : dataSource.current();
  }

  Generation drainingGeneration() {
    return draining;
  }
}
