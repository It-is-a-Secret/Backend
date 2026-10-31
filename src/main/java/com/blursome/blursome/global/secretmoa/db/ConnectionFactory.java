package com.blursome.blursome.global.secretmoa.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** 주어진 계정으로 물리 커넥션을 연다. 테스트에서 가짜 커넥션을 넣기 위해 분리했다. */
@FunctionalInterface
public interface ConnectionFactory {

  Connection connect(String username, String password) throws SQLException;

  /** 블라썸 기존 {@code DB_URL}(옵션 포함)로 접속한다. SecretMoa가 돌려주는 JDBC URL은 쓰지 않는다 (R6). */
  static ConnectionFactory driverManager(String jdbcUrl) {
    return (username, password) -> {
      Properties properties = new Properties();
      properties.setProperty("user", username);
      properties.setProperty("password", password);
      return DriverManager.getConnection(jdbcUrl, properties);
    };
  }
}
