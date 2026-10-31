package com.blursome.blursome.global.secretmoa.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.global.secretmoa.DbLease;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 세대 전환 불변식(R4)과 커넥션 종료 집계를 검증한다. */
class LeaseAwareDataSourceTest {

  static DbLease lease(int n) {
    return new DbLease(UUID.randomUUID(), "sm_user" + n, "pw-" + n, Instant.now().plus(Duration.ofHours(1)),
        Duration.ofHours(1));
  }

  /** close·abort·isClosed·createStatement(SELECT 1 검증용)만 아는 가짜 커넥션. */
  static Connection fakeConnection(AtomicInteger physicalCloses) {
    AtomicBoolean closed = new AtomicBoolean();
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "close", "abort" -> {
            if (closed.compareAndSet(false, true)) {
              physicalCloses.incrementAndGet();
            }
            yield null;
          }
          case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class}, (p, m, a) -> "execute".equals(m.getName()) ? Boolean.TRUE : null);
          case "isClosed" -> closed.get();
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });
  }

  @Test
  @DisplayName("열면 카운터가 오르고 close하면 내려간다. close를 여러 번 불러도 한 번만 내려간다")
  void closeTwice_releasesOnce() throws SQLException {
    AtomicInteger closes = new AtomicInteger();
    Generation g1 = new Generation(1, lease(1));
    LeaseAwareDataSource ds = new LeaseAwareDataSource((u, p) -> fakeConnection(closes), g1);

    Connection c = ds.getConnection();
    assertThat(g1.inFlightAndOpen()).isEqualTo(1);
    c.close();
    c.close();

    assertThat(g1.inFlightAndOpen()).isZero();
    assertThat(closes).hasValue(1);
  }

  @Test
  @DisplayName("abort로 끝나도 한 번만 내려가고, abort 뒤 close도 다시 내리지 않는다")
  void abortThenClose_releasesOnce() throws SQLException {
    Generation g1 = new Generation(1, lease(1));
    LeaseAwareDataSource ds = new LeaseAwareDataSource((u, p) -> fakeConnection(new AtomicInteger()), g1);
    Connection a = ds.getConnection();
    Connection b = ds.getConnection();

    a.abort(Runnable::run);
    a.close();

    assertThat(g1.inFlightAndOpen()).isEqualTo(1);
    b.close();
    assertThat(g1.inFlightAndOpen()).isZero();
  }

  @Test
  @DisplayName("접속에 실패하면 올렸던 카운터를 되돌린다")
  void connectFailure_restoresCounter() {
    Generation g1 = new Generation(1, lease(1));
    LeaseAwareDataSource ds = new LeaseAwareDataSource((u, p) -> {
      throw new SQLException("Access denied", "28000", 1045);
    }, g1);

    assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class);
    assertThat(g1.inFlightAndOpen()).isZero();

    LeaseAwareDataSource runtime = new LeaseAwareDataSource((u, p) -> {
      throw new IllegalStateException("driver bug");
    }, g1);
    assertThatThrownBy(runtime::getConnection).isInstanceOf(IllegalStateException.class);
    assertThat(g1.inFlightAndOpen()).isZero();
  }

  @Test
  @DisplayName("현재 세대의 계정으로 접속하고, 다른 계정으로 접속하는 경로는 막는다")
  void usesCurrentGenerationAccount() throws SQLException {
    Set<String> users = ConcurrentHashMap.newKeySet();
    LeaseAwareDataSource ds = new LeaseAwareDataSource((u, p) -> {
      users.add(u + "/" + p);
      return fakeConnection(new AtomicInteger());
    }, new Generation(1, lease(1)));

    ds.getConnection().close();
    Generation old = ds.switchTo(new Generation(2, lease(2)));
    ds.getConnection().close();

    assertThat(old.number()).isEqualTo(1);
    assertThat(users).containsExactlyInAnyOrder("sm_user1/pw-1", "sm_user2/pw-2");
    assertThatThrownBy(() -> ds.getConnection("x", "y")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("경쟁 조건: 32 스레드가 접속을 반복하는 동안 세대를 계속 바꿔도, 이전 세대 카운터는 0이 된 뒤 다시 오르지 않는다")
  void concurrentSwitch_oldCounterNeverIncreasesAfterZero() throws Exception {
    int threads = 32;
    int iterationsPerThread = 3000;
    int switches = 60;
    LeaseAwareDataSource ds = new LeaseAwareDataSource((u, p) -> {
      if ((u.hashCode() & 7) == 0) {
        Thread.onSpinWait();
      }
      return fakeConnection(new AtomicInteger());
    }, new Generation(1, lease(1)));

    AtomicBoolean violation = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    List<Generation> retired = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        workers.add(pool.submit(() -> {
          start.await();
          for (int i = 0; i < iterationsPerThread; i++) {
            Connection c = ds.getConnection();
            if ((i & 3) == 0) {
              Thread.yield();
            }
            c.close();
          }
          return null;
        }));
      }
      // 감시자: 전환된 세대마다 0을 본 뒤 다시 양수가 되면 위반
      Future<?> watcher = pool.submit(() -> {
        Set<Generation> seenZero = ConcurrentHashMap.newKeySet();
        while (!done.get()) {
          List<Generation> snapshot;
          synchronized (retired) {
            snapshot = new ArrayList<>(retired);
          }
          for (Generation g : snapshot) {
            int value = g.inFlightAndOpen();
            if (value == 0) {
              seenZero.add(g);
            } else if (seenZero.contains(g)) {
              violation.set(true);
            }
          }
        }
      });
      start.countDown();
      for (int s = 2; s <= switches + 1; s++) {
        Thread.sleep(2);
        Generation old = ds.switchTo(new Generation(s, lease(s)));
        synchronized (retired) {
          retired.add(old);
        }
      }
      for (Future<?> w : workers) {
        w.get(60, TimeUnit.SECONDS);
      }
      done.set(true);
      watcher.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(violation).as("이전 세대 카운터가 0 이후 증가함").isFalse();
    assertThat(retired).hasSize(switches).allSatisfy(g -> assertThat(g.inFlightAndOpen()).isZero());
    assertThat(ds.current().inFlightAndOpen()).isZero();
  }
}
