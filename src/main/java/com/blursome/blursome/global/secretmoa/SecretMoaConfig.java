package com.blursome.blursome.global.secretmoa;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * SecretMoa 클라이언트 구성. S3나 DB 기능이 하나라도 켜졌을 때만 Bean을 만든다.
 * 둘 다 꺼져 있으면(기본) 이 구성은 아무것도 등록하지 않아 기존 동작과 같다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.secretmoa.s3.enabled:false} or ${app.secretmoa.db.enabled:false}")
public class SecretMoaConfig {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  public SecretMoaClient secretMoaClient(SecretMoaProperties properties, RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);

    RestClient restClient = restClientBuilder.clone()
        .baseUrl(properties.url().toString())
        .requestFactory(requestFactory)
        .build();
    return new SecretMoaClient(restClient, properties.apiKey(), properties.instanceId(), objectMapper);
  }
}
