package com.blursome.blursome.global.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트/프리사이너 빈 구성. 자격증명은 IAM 역할이 아니라 {@code .env}로 주입된
 * access/secret key를 사용한다(운영 단순화 목적, {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-5).
 *
 * <p>빈 생성은 네트워크 호출을 하지 않으므로, 더미 placeholder 자격증명/버킷만으로도 컨텍스트가
 * 정상 로딩된다. 실제 S3 호출 검증은 #55 통합에서 수행한다.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

  private final S3Properties properties;

  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentialsProvider())
        .build();
  }

  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentialsProvider())
        .build();
  }

  private StaticCredentialsProvider credentialsProvider() {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }
}
