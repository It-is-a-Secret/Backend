package com.blursome.blursome.global.secretmoa.db;

import com.blursome.blursome.global.secretmoa.DbLease;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 임시 계정 하나(Lease)와, 그 계정으로 접속 중이거나 열려 있는 커넥션 수.
 *
 * <p>카운터는 {@link LeaseAwareDataSource}가 세대를 고를 때 같은 락 안에서 올린다. 세대가 전환된 뒤에는
 * 아무도 이 세대를 새로 고르지 않으므로 카운터는 감소만 한다. 그래서 0을 본 순간 이후로 이 계정의 커넥션은
 * 생기지 않고, 회수해도 진행 중인 트랜잭션을 끊지 않는다 (R3, R4).
 */
public final class Generation {

  private static final Logger log = LoggerFactory.getLogger(Generation.class);

  private final long number;
  private final DbLease lease;
  private final AtomicInteger inFlightAndOpen = new AtomicInteger();

  public Generation(long number, DbLease lease) {
    this.number = number;
    this.lease = lease;
  }

  void acquire() {
    inFlightAndOpen.incrementAndGet();
  }

  void release() {
    if (inFlightAndOpen.decrementAndGet() < 0) {
      // 짝이 맞지 않는 감소. 회수 판단이 틀어지지 않도록 0으로 되돌리고 기록한다.
      inFlightAndOpen.set(0);
      log.error("SecretMoa DB generation {} connection count went negative", number);
    }
  }

  /** 접속 중이거나 열려 있는 커넥션 수. */
  public int inFlightAndOpen() {
    return inFlightAndOpen.get();
  }

  public long number() {
    return number;
  }

  public UUID leaseId() {
    return lease.leaseId();
  }

  public Instant expiresAt() {
    return lease.expiresAt();
  }

  String username() {
    return lease.username();
  }

  String password() {
    return lease.password();
  }

  @Override
  public String toString() {
    return "Generation[" + number + ", leaseId=" + lease.leaseId() + ", inFlightAndOpen=" + inFlightAndOpen.get() + "]";
  }
}
