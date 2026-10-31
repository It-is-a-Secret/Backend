package com.blursome.blursome.global.secretmoa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** SecretMoa 설정 바인딩, 기동 시 검증, 플래그에 따른 클라이언트 등록을 검증한다. */
class SecretMoaPropertiesTest {

  private static final String KEY = "smk_00000000-0000-0000-0000-000000000000.secret-value-should-not-leak";
  private static final String ID_1 = "11111111-1111-1111-1111-111111111111";
  private static final String ID_2 = "22222222-2222-2222-2222-222222222222";

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SecretMoaProperties.class)
  @Import(SecretMoaConfig.class)
  static class TestConfig {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, RestClientAutoConfiguration.class))
      .withUserConfiguration(TestConfig.class);

  private static String[] s3Enabled() {
    return new String[] {
        "app.secretmoa.api-key=" + KEY,
        "app.secretmoa.s3.enabled=true",
        "app.secretmoa.s3.originals.target-id=" + ID_1,
        "app.secretmoa.s3.originals.role-id=" + ID_2,
        "app.secretmoa.s3.variants.target-id=" + ID_1,
        "app.secretmoa.s3.variants.role-id=" + ID_2};
  }

  @Test
  @DisplayName("설정이 없으면 모두 꺼진 기본값으로 바인딩되고 클라이언트 Bean을 만들지 않는다")
  void defaults_allDisabled_noClient() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      SecretMoaProperties p = context.getBean(SecretMoaProperties.class);
      assertThat(p.anyEnabled()).isFalse();
      assertThat(p.url()).hasToString("http://127.0.0.1:8081");
      assertThat(p.s3().minTtl()).isEqualTo(Duration.ofMinutes(30));
      assertThat(p.db().renewBefore()).isEqualTo(Duration.ofMinutes(20));
      assertThat(p.instanceId()).isEqualTo("unknown");
      assertThat(context).doesNotHaveBean(SecretMoaClient.class);
    });
  }

  @Test
  @DisplayName("꺼진 상태에서는 환경변수 기본값처럼 빈 ID가 들어와도 기동한다")
  void disabled_blankIds_ok() {
    runner.withPropertyValues(
            "app.secretmoa.api-key=",
            "app.secretmoa.s3.originals.target-id=",
            "app.secretmoa.db.database-id=")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("S3를 켜고 API Key와 ID가 있으면 클라이언트 Bean이 생긴다")
  void s3Enabled_valid_createsClient() {
    runner.withPropertyValues(s3Enabled()).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(SecretMoaClient.class);
      SecretMoaProperties p = context.getBean(SecretMoaProperties.class);
      assertThat(p.s3().originals().targetId()).isEqualTo(UUID.fromString(ID_1));
    });
  }

  @Test
  @DisplayName("기능을 켰는데 API Key가 없으면 기동이 실패한다")
  void enabled_withoutApiKey_fails() {
    runner.withPropertyValues("app.secretmoa.db.enabled=true",
            "app.secretmoa.db.database-id=" + ID_1, "app.secretmoa.db.role-id=" + ID_2)
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("app.secretmoa.api-key"));
  }

  @Test
  @DisplayName("S3를 켰는데 Target·Role ID가 없으면 기동이 실패한다")
  void s3Enabled_withoutIds_fails() {
    runner.withPropertyValues("app.secretmoa.api-key=" + KEY, "app.secretmoa.s3.enabled=true",
            "app.secretmoa.s3.originals.target-id=" + ID_1)
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("app.secretmoa.s3.originals"));
  }

  @Test
  @DisplayName("ID가 UUID 형식이 아니면 기동이 실패한다")
  void invalidUuid_fails() {
    runner.withPropertyValues("app.secretmoa.db.database-id=not-a-uuid")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("DB check-interval이 renew-before의 1/3보다 길면 기동이 실패한다")
  void db_checkIntervalTooLong_fails() {
    runner.withPropertyValues("app.secretmoa.api-key=" + KEY, "app.secretmoa.db.enabled=true",
            "app.secretmoa.db.database-id=" + ID_1, "app.secretmoa.db.role-id=" + ID_2,
            "app.secretmoa.db.renew-before=180s", "app.secretmoa.db.check-interval=61s",
            "app.secretmoa.db.drain-alarm-before=30s")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("check-interval"));
  }

  @Test
  @DisplayName("rotation-test 타이밍(renew 180초, check 10초, drain 30초)은 통과한다")
  void db_rotationTestTiming_ok() {
    runner.withPropertyValues("app.secretmoa.api-key=" + KEY, "app.secretmoa.db.enabled=true",
            "app.secretmoa.db.database-id=" + ID_1, "app.secretmoa.db.role-id=" + ID_2,
            "app.secretmoa.db.renew-before=180s", "app.secretmoa.db.check-interval=10s",
            "app.secretmoa.db.drain-alarm-before=30s")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("S3 stale-before가 prefetch-before보다 길면 기동이 실패한다")
  void s3_staleNotBeforePrefetch_fails() {
    runner.withPropertyValues(s3Enabled())
        .withPropertyValues("app.secretmoa.s3.stale-before=20m")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("stale-before"));
  }

  @Test
  @DisplayName("URL이 http(s)가 아니면 기동이 실패한다")
  void enabled_nonHttpUrl_fails() {
    runner.withPropertyValues(s3Enabled())
        .withPropertyValues("app.secretmoa.url=ftp://127.0.0.1:8081")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("app.secretmoa.url"));
  }

  @Test
  @DisplayName("toString과 기동 실패 메시지에 API Key가 남지 않는다")
  void apiKey_isNeverPrinted() {
    runner.withPropertyValues(s3Enabled()).run(context ->
        assertThat(context.getBean(SecretMoaProperties.class).toString())
            .doesNotContain(KEY).doesNotContain("secret-value").contains("apiKey=***"));

    runner.withPropertyValues("app.secretmoa.api-key=" + KEY, "app.secretmoa.s3.enabled=true")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().satisfies(failure -> {
              Throwable t = failure;
              while (t != null) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("secret-value");
                t = t.getCause();
              }
            }));
  }

  @Test
  @DisplayName("instance-id는 SecretMoa 형식으로 정규화된다")
  void instanceId_isNormalized() {
    assertThat(SecretMoaProperties.normalizeInstanceId("local,maillog-my host")).isEqualTo("local-maillog-my-host");
    assertThat(SecretMoaProperties.normalizeInstanceId(" ")).isEqualTo("unknown");
    assertThat(SecretMoaProperties.normalizeInstanceId("a".repeat(100))).hasSize(64);
    assertThat(SecretMoaProperties.normalizeInstanceId("prod-ip-10-0-0-1_x")).isEqualTo("prod-ip-10-0-0-1_x");
  }
}
