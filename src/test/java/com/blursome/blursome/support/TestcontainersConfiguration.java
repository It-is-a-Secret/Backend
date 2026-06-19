package com.blursome.blursome.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트(@SpringBootTest)에서 실제 MySQL 8 컨테이너를 띄워 DataSource로 연결한다.
 *
 * <p>{@link ServiceConnection}이 컨테이너의 접속 정보를 Spring 데이터소스에 자동 주입하므로
 * url/username/password를 따로 설정할 필요가 없다. 운영과 동일한 MySQL로 검증해
 * 방언(dialect)·DDL·네이티브 쿼리 충실도를 확보한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
  }
}
