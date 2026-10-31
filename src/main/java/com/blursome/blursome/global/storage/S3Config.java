package com.blursome.blursome.global.storage;

import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import com.blursome.blursome.global.secretmoa.SecretMoaS3CredentialsProvider;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트/프리사이너 빈 구성. 기본 자격증명은 {@code .env}로 주입된 access/secret key를 사용한다
 * (운영 단순화 목적, {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-5).
 *
 * <p>{@code app.secretmoa.s3.enabled=true}면 클라이언트의 기본 자격증명은 항상 실패하는 공급자로 바뀌고,
 * 모든 요청은 {@link S3CredentialRouting}이 지정한 SecretMoa 임시 자격증명으로만 나간다. 지정을 빠뜨린
 * 요청이 정적 키로 조용히 나가지 않게 하기 위함이다. 이때 {@code S3_ACCESS_KEY}·{@code S3_SECRET_KEY}는 비워도 된다.
 *
 * <p>빈 생성은 네트워크 호출을 하지 않으므로, 더미 placeholder 자격증명/버킷만으로도 컨텍스트가
 * 정상 로딩된다. SecretMoa가 켜져 있으면 버킷별 자격증명을 한 번씩 발급해 설정 오류를 기동 시 드러낸다.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

  /** SecretMoa S3가 켜졌을 때 클라이언트 기본 자격증명. 요청별 지정을 빠뜨리면 즉시 실패한다. */
  static final AwsCredentialsProvider STATIC_CREDENTIALS_DISABLED = () -> {
    throw new IllegalStateException(
        "Static S3 credentials are disabled while app.secretmoa.s3.enabled=true. Route the request through S3CredentialRouting");
  };

  private final S3Properties properties;
  private final SecretMoaProperties secretMoaProperties;

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

  /**
   * 버킷별 SecretMoa 자격증명 라우팅. SecretMoa S3가 꺼져 있으면 요청을 건드리지 않는 비활성 라우팅이다.
   * 켜져 있으면 두 버킷의 자격증명을 한 번씩 발급해 ID·버킷·TTL이 맞는지 기동 시 확인한다.
   */
  @Bean
  public S3CredentialRouting s3CredentialRouting(ObjectProvider<SecretMoaClient> secretMoaClient, Clock clock) {
    SecretMoaProperties.S3 s3 = secretMoaProperties.s3();
    if (!s3.enabled()) {
      return S3CredentialRouting.disabled();
    }
    SecretMoaClient client = secretMoaClient.getObject();
    SecretMoaS3CredentialsProvider originals = new SecretMoaS3CredentialsProvider("originals",
        () -> client.issueS3Credential(s3.originals().targetId(), s3.originals().roleId()),
        properties.originalsBucket(), true, s3, clock);
    SecretMoaS3CredentialsProvider variants = new SecretMoaS3CredentialsProvider("variants",
        () -> client.issueS3Credential(s3.variants().targetId(), s3.variants().roleId()),
        properties.variantsBucket(), false, s3, clock);
    S3CredentialRouting routing = S3CredentialRouting.of(originals, variants);
    try {
      originals.resolveCredentials();
      variants.resolveCredentials();
    } catch (RuntimeException e) {
      routing.close();
      throw e;
    }
    return routing;
  }

  private AwsCredentialsProvider credentialsProvider() {
    if (secretMoaProperties.s3().enabled()) {
      return STATIC_CREDENTIALS_DISABLED;
    }
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }
}
