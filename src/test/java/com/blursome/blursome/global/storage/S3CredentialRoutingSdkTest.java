package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 실제 AWS SDK 서명 경로에서 요청별 자격증명이 쓰이는지 검증한다. 네트워크는 쓰지 않는다
 * (프리사이너는 서명만 계산하고, S3Client는 요청을 기록하는 가짜 HTTP 클라이언트를 쓴다).
 *
 * <p>클라이언트 기본 자격증명은 SecretMoa 활성 시와 같은 "항상 실패" 공급자다. 그래서 요청별 지정이
 * 무시되거나 기본 공급자를 먼저 조회하면 테스트가 실패한다.
 */
class S3CredentialRoutingSdkTest {

  private static final S3Properties PROPERTIES = new S3Properties("test-originals", "test-variants",
      "https://test-variants.example/", "ap-northeast-2", Duration.ofMinutes(5), Duration.ofMinutes(5), "", "");

  private final StaticCredentialsProvider originals = StaticCredentialsProvider.create(
      AwsSessionCredentials.create("ASIAORIGINALS0000001", "originals-secret", "originals-token"));
  private final StaticCredentialsProvider variants = StaticCredentialsProvider.create(
      AwsSessionCredentials.create("ASIAVARIANTS00000001", "variants-secret", "variants-token"));
  private final S3CredentialRouting routing = S3CredentialRouting.of(originals, variants);

  private final RecordingHttpClient http = new RecordingHttpClient();
  private final S3Presigner presigner = S3Presigner.builder()
      .region(Region.AP_NORTHEAST_2)
      .credentialsProvider(S3Config.STATIC_CREDENTIALS_DISABLED)
      .build();
  private final S3Client client = S3Client.builder()
      .region(Region.AP_NORTHEAST_2)
      .credentialsProvider(S3Config.STATIC_CREDENTIALS_DISABLED)
      .httpClient(http)
      .build();
  private final S3StorageService service = new S3StorageService(presigner, client, PROPERTIES, routing);

  @AfterEach
  void close() {
    presigner.close();
    client.close();
  }

  private static String decoded(String url) {
    return URLDecoder.decode(url, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Presigned PUT·GET은 originals 임시 자격증명과 세션 토큰으로 서명된다")
  void presign_usesOriginalsCredentials() {
    String put = decoded(service.presignOriginalUpload("originals/1/a.jpg", "image/jpeg", 70).uploadUrl());
    String get = decoded(service.presignOriginalDownload("originals/1/a.jpg"));

    for (String url : List.of(put, get)) {
      assertThat(url).contains("X-Amz-Credential=ASIAORIGINALS0000001/")
          .contains("X-Amz-Security-Token=originals-token")
          .doesNotContain("ASIAVARIANTS");
    }
  }

  @Test
  @DisplayName("HeadObject는 버킷별 임시 자격증명으로 서명된다")
  void headObject_usesPerBucketCredentials() {
    assertThat(service.existsVariant("variants/1/a.webp")).isTrue();
    assertThat(service.findOriginal("originals/1/a.jpg")).isPresent();

    assertThat(http.requests).hasSize(2);
    SdkHttpRequest variantHead = http.requests.get(0);
    SdkHttpRequest originalHead = http.requests.get(1);
    assertThat(variantHead.host()).startsWith("test-variants.");
    assertThat(variantHead.firstMatchingHeader("Authorization")).get().asString()
        .contains("Credential=ASIAVARIANTS00000001/");
    assertThat(variantHead.firstMatchingHeader("X-Amz-Security-Token")).contains("variants-token");
    assertThat(originalHead.host()).startsWith("test-originals.");
    assertThat(originalHead.firstMatchingHeader("Authorization")).get().asString()
        .contains("Credential=ASIAORIGINALS0000001/");
  }

  @Test
  @DisplayName("라우팅을 거치지 않은 요청은 정적 키로 나가지 않고 실패한다")
  void unroutedRequest_failsClosed() {
    HeadObjectRequest unrouted = HeadObjectRequest.builder().bucket("test-originals").key("x").build();

    assertThatThrownBy(() -> client.headObject(unrouted))
        .hasStackTraceContaining("S3CredentialRouting");
    assertThat(http.requests).isEmpty();
  }

  /** 요청을 기록하고 빈 200 응답을 돌려주는 가짜 HTTP 클라이언트. */
  static final class RecordingHttpClient implements SdkHttpClient {

    final List<SdkHttpRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
      requests.add(request.httpRequest());
      return new ExecutableHttpRequest() {
        @Override
        public HttpExecuteResponse call() {
          return HttpExecuteResponse.builder()
              .response(SdkHttpResponse.builder().statusCode(200).putHeader("Content-Length", "0").build())
              .build();
        }

        @Override
        public void abort() {
        }
      };
    }

    @Override
    public void close() {
    }
  }
}
