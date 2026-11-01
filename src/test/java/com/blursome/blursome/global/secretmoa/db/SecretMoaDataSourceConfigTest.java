package com.blursome.blursome.global.secretmoa.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** DB 로테이션 구성의 등록 조건과 기동 시 설정 검증을 확인한다. SecretMoa는 호출하지 않는다. */
class SecretMoaDataSourceConfigTest {

  private final SecretMoaClient client = mock(SecretMoaClient.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({SecretMoaProperties.class, DataSourceProperties.class})
  @Import(SecretMoaDataSourceConfig.class)
  static class TestConfig {
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class)
        .withBean(SecretMoaClient.class, () -> client)
        .withBean(Clock.class, Clock::systemUTC);
  }

  private static final String[] DB_ENABLED = {
      "app.secretmoa.api-key=smk_test",
      "app.secretmoa.db.enabled=true",
      "app.secretmoa.db.database-id=11111111-1111-1111-1111-111111111111",
      "app.secretmoa.db.role-id=22222222-2222-2222-2222-222222222222",
      "spring.datasource.url=jdbc:mysql://127.0.0.1:1/none"};

  @Test
  @DisplayName("DB 기능이 꺼져 있으면 로테이터와 DataSource를 등록하지 않는다")
  void disabled_registersNothing() {
    runner().run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(DbLeaseRotator.class);
      assertThat(context).doesNotHaveBean("dataSource");
    });
    verifyNoInteractions(client);
  }

  @Test
  @DisplayName("Hikari max-lifetime이 renew-before 이상이면(기본 30분 > 20분) SecretMoa 호출 전에 기동이 실패한다")
  void maxLifetimeTooLong_failsBeforeIssuing() {
    runner().withPropertyValues(DB_ENABLED).run(context -> assertThat(context).hasFailed()
        .getFailure().rootCause().hasMessageContaining("max-lifetime"));
    verifyNoInteractions(client);
  }

  @Test
  @DisplayName("rotation-test 타이밍에서 max-lifetime 60초는 renew-before 180초보다 짧아 검증을 통과한다")
  void rotationTestTiming_passesPoolValidation() {
    runner().withPropertyValues(DB_ENABLED)
        .withPropertyValues("app.secretmoa.db.renew-before=180s", "app.secretmoa.db.check-interval=10s",
            "app.secretmoa.db.drain-alarm-before=30s", "spring.datasource.hikari.max-lifetime=60000")
        // 풀 검증을 통과하면 첫 Lease를 발급하려 한다. mock은 null을 돌려주므로 그 단계에서 멈춘다.
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageNotContaining("max-lifetime"));
  }

  @Test
  @DisplayName("DB_URL이 없으면 기동이 실패한다")
  void missingUrl_fails() {
    runner().withPropertyValues(DB_ENABLED).withPropertyValues("spring.datasource.url=")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("DB_URL"));
  }
}
