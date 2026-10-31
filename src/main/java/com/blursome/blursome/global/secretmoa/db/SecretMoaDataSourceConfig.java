package com.blursome.blursome.global.secretmoa.db;

import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@code app.secretmoa.db.enabled=true}일 때 Hikari 풀이 SecretMoa DB 임시 계정을 쓰도록 DataSource를 바꾼다.
 * 꺼져 있으면(기본) 아무것도 등록하지 않아 Spring Boot 기본 DataSource({@code DB_USERNAME}·{@code DB_PASSWORD})를 쓴다.
 *
 * <p>Hikari 설정은 {@code spring.datasource.hikari.*}를 직접 바인딩한다. Bean 메서드에
 * {@code @ConfigurationProperties}를 붙이면 메서드가 반환된 뒤에 바인딩되어 여기서 정한 값을 yml이 덮어쓴다.
 *
 * <p>종료 순서는 로테이터가 관리한다(풀 종료 → Lease 회수). 그래서 DataSource Bean의 destroy 메서드를 비운다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.secretmoa.db.enabled", havingValue = "true")
public class SecretMoaDataSourceConfig {

  @Bean(destroyMethod = "close")
  public DbLeaseRotator dbLeaseRotator(SecretMoaClient secretMoaClient, SecretMoaProperties properties,
      DataSourceProperties dataSourceProperties, Clock clock) {
    String jdbcUrl = dataSourceProperties.getUrl();
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalStateException("spring.datasource.url (DB_URL) is required when SecretMoa DB is enabled");
    }
    return new DbLeaseRotator(secretMoaClient, properties.db(), clock, ConnectionFactory.driverManager(jdbcUrl));
  }

  @Bean(destroyMethod = "")
  public HikariDataSource dataSource(DbLeaseRotator rotator, SecretMoaProperties properties, Environment environment) {
    HikariConfig config = Binder.get(environment)
        .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class))
        .orElseGet(HikariConfig::new);
    DbLeaseRotator.validatePool(config, properties.db());
    if (config.getPoolName() == null) {
      config.setPoolName("SecretMoaHikariPool");
    }
    config.setDataSource(rotator.start());
    HikariDataSource pool = new HikariDataSource(config);
    rotator.attach(pool);
    rotator.startSchedule();
    return pool;
  }
}
