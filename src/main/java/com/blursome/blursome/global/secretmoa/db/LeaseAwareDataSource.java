package com.blursome.blursome.global.secretmoa.db;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Hikari에 넘기는 DataSource. 현재 세대(SecretMoa DB 임시 계정)로 물리 커넥션을 연다.
 *
 * <p><b>불변식</b>: 세대 선택과 카운터 증가를 한 락 안에서 하고, 접속 "전"에 증가한다. 그래서
 * {@link #switchTo}가 반환된 뒤에는 이전 세대로 새 접속이 시작되지 않고, 이전 세대 카운터는 감소만 한다.
 * 접속에 실패하면 증가를 되돌린다.
 */
public final class LeaseAwareDataSource extends AbstractDataSource {

  private final ConnectionFactory connectionFactory;
  private final Object lock = new Object();
  private Generation current;

  public LeaseAwareDataSource(ConnectionFactory connectionFactory, Generation initial) {
    this.connectionFactory = connectionFactory;
    this.current = initial;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Generation generation;
    synchronized (lock) {
      generation = current;
      generation.acquire();
    }
    Connection raw;
    try {
      raw = connectionFactory.connect(generation.username(), generation.password());
    } catch (SQLException | RuntimeException | Error e) {
      generation.release();
      throw e;
    }
    return CloseTracking.wrap(raw, generation::release);
  }

  /** 계정은 세대가 정한다. 다른 계정으로 접속하는 경로는 막는다. */
  @Override
  public Connection getConnection(String username, String password) {
    throw new UnsupportedOperationException("LeaseAwareDataSource uses the current SecretMoa lease");
  }

  /** 새 세대로 전환하고 이전 세대를 돌려준다. 반환 뒤 이전 세대로 새 접속이 시작되지 않는다. */
  public Generation switchTo(Generation next) {
    synchronized (lock) {
      Generation old = current;
      current = next;
      return old;
    }
  }

  public Generation current() {
    synchronized (lock) {
      return current;
    }
  }
}
