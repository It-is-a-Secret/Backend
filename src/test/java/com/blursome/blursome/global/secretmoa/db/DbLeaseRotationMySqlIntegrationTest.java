package com.blursome.blursome.global.secretmoa.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.secretmoa.DbLease;
import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import com.blursome.blursome.global.secretmoa.db.DbLeaseRotatorTest.MutableClock;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL과 실제 계정 2개로 세대 전환을 검증한다. SecretMoa 대신 테스트가 계정을 만들고, 회수는 SecretMoa와
 * 같은 순서(계정 잠금 → 세션 KILL → 삭제)로 흉내 낸다. 그래서 회수가 너무 이르면 진행 중인 트랜잭션이 끊겨
 * 테스트가 실패한다 (R3).
 */
@Testcontainers
class DbLeaseRotationMySqlIntegrationTest {

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

  private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(60);
  private static final SecretMoaProperties.Db SETTINGS = new SecretMoaProperties.Db(true, UUID.randomUUID(),
      UUID.randomUUID(), Duration.ofMinutes(20), Duration.ofMinutes(3), Duration.ofSeconds(60));

  private final MutableClock clock = new MutableClock(T0);
  private final SecretMoaClient client = mock(SecretMoaClient.class);
  private final AtomicInteger issued = new AtomicInteger();
  private final Map<UUID, String> accounts = new ConcurrentHashMap<>();
  private DbLeaseRotator rotator;
  private HikariDataSource pool;

  @BeforeAll
  static void schema() throws SQLException {
    root("CREATE TABLE IF NOT EXISTS item (id INT AUTO_INCREMENT PRIMARY KEY, note VARCHAR(50))");
  }

  private static void root(String... sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        Statement s = c.createStatement()) {
      for (String q : sql) {
        s.execute(q);
      }
    }
  }

  private static long count(String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  /** SecretMoa 발급 흉내: DML만 가진 새 계정을 만든다. */
  private DbLease issue() {
    int n = issued.incrementAndGet();
    String user = "sm_it" + n + "_" + System.nanoTime() % 100000;
    String password = "It-pass-" + UUID.randomUUID();
    try {
      root("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'",
          "GRANT SELECT, INSERT, UPDATE, DELETE ON " + MYSQL.getDatabaseName() + ".* TO '" + user + "'@'%'");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    UUID leaseId = UUID.randomUUID();
    accounts.put(leaseId, user);
    return new DbLease(leaseId, user, password, clock.instant().plus(TTL), TTL);
  }

  /** SecretMoa 회수 흉내: 잠금 → 남은 세션 KILL → 삭제. */
  private void revoke(UUID leaseId) throws SQLException {
    String user = accounts.remove(leaseId);
    List<Long> sessions = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        Statement s = c.createStatement()) {
      s.execute("ALTER USER '" + user + "'@'%' ACCOUNT LOCK");
      try (ResultSet r = s.executeQuery("SELECT ID FROM information_schema.PROCESSLIST WHERE USER='" + user + "'")) {
        while (r.next()) {
          sessions.add(r.getLong(1));
        }
      }
      for (long id : sessions) {
        s.execute("KILL CONNECTION " + id);
      }
      s.execute("DROP USER '" + user + "'@'%'");
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    root("DELETE FROM item");
    given(client.issueDbCredential(any(), any())).willAnswer(inv -> issue());
    doAnswer(inv -> {
      revoke(inv.getArgument(0));
      return null;
    }).when(client).revokeDbLease(any());
    rotator = new DbLeaseRotator(client, SETTINGS, clock, ConnectionFactory.driverManager(MYSQL.getJdbcUrl()));
    HikariConfig config = new HikariConfig();
    config.setMaxLifetime(Duration.ofMinutes(15).toMillis());
    // 고정 크기 풀. 비동기 풀 채우기가 세대 전환과 겹치지 않도록 다 찰 때까지 기다린 뒤 시작한다.
    config.setMinimumIdle(3);
    config.setMaximumPoolSize(3);
    DbLeaseRotator.validatePool(config, SETTINGS);
    config.setDataSource(rotator.start());
    pool = new HikariDataSource(config);
    rotator.attach(pool);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (pool.getHikariPoolMXBean().getTotalConnections() < 3 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(3);
  }

  @AfterEach
  void tearDown() {
    rotator.close();
  }

  private static String currentUser(Connection c) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CURRENT_USER()")) {
      r.next();
      return r.getString(1).split("@")[0];
    }
  }

  @Test
  @DisplayName("세대 전환 중 진행 중인 트랜잭션은 끊기지 않고, 커넥션이 반납된 뒤에야 이전 계정이 회수된다")
  void rotationDoesNotCutInFlightTransaction() throws Exception {
    String firstUser = accounts.values().iterator().next();
    Connection tx = pool.getConnection();
    tx.setAutoCommit(false);
    assertThat(currentUser(tx)).isEqualTo(firstUser);
    try (Statement s = tx.createStatement()) {
      s.executeUpdate("INSERT INTO item(note) VALUES ('before-rotation')");
    }

    clock.set(T0.plus(TTL).minus(SETTINGS.renewBefore()));
    rotator.tick();
    assertThat(rotator.currentGeneration().number()).isEqualTo(2);

    // 새 커넥션은 새 계정을 쓴다
    try (Connection fresh = pool.getConnection()) {
      assertThat(currentUser(fresh)).isNotEqualTo(firstUser);
    }

    // 이전 세대 커넥션(진행 중 트랜잭션)이 남아 있으므로 회수하지 않는다
    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();
    verify(client, never()).revokeDbLease(rotator.drainingGeneration().leaseId());
    assertThat(count("SELECT COUNT(*) FROM mysql.user WHERE User='" + firstUser + "'")).isEqualTo(1);

    // 진행 중 트랜잭션은 이전 계정으로 계속 진행되고 커밋된다
    try (Statement s = tx.createStatement()) {
      s.executeUpdate("INSERT INTO item(note) VALUES ('during-rotation')");
    }
    tx.commit();
    tx.close(); // 소프트 퇴출된 커넥션이라 반납 시 물리 커넥션이 닫힌다 (Hikari가 별도 스레드에서 비동기로 닫음)
    Generation old = rotator.drainingGeneration();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (old.inFlightAndOpen() > 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(old.inFlightAndOpen()).isZero();

    clock.set(clock.instant().plusSeconds(60));
    rotator.tick();

    assertThat(rotator.drainingGeneration()).isNull();
    assertThat(count("SELECT COUNT(*) FROM mysql.user WHERE User='" + firstUser + "'")).isZero();
    assertThat(count("SELECT COUNT(*) FROM item")).isEqualTo(2);

    // 회수 후에도 풀은 새 계정으로 정상 동작한다
    try (Connection after = pool.getConnection(); Statement s = after.createStatement()) {
      s.executeUpdate("INSERT INTO item(note) VALUES ('after-revoke')");
    }
    assertThat(count("SELECT COUNT(*) FROM item")).isEqualTo(3);
  }

  @Test
  @DisplayName("임시 계정은 DML만 가진다: DDL은 거부된다")
  void leaseAccountCannotRunDdl() throws Exception {
    try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
      assertThat(s.executeUpdate("INSERT INTO item(note) VALUES ('dml-ok')")).isEqualTo(1);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.execute("CREATE TABLE nope (id INT)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  @DisplayName("종료하면 풀을 닫고 모든 임시 계정을 회수해 남은 계정이 없다")
  void closeRevokesAll() throws Exception {
    clock.set(T0.plus(TTL).minus(SETTINGS.renewBefore()));
    rotator.tick();
    assertThat(accounts).hasSize(2);

    rotator.close();

    assertThat(pool.isClosed()).isTrue();
    assertThat(accounts).isEmpty();
    assertThat(count("SELECT COUNT(*) FROM mysql.user WHERE User LIKE 'sm\\_it%'")).isZero();
  }
}
