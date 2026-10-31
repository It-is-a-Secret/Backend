package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** {@link S3StorageService}의 모든 요청이 버킷에 맞는 자격증명으로 라우팅되는지 검증한다. */
class S3StorageServiceRoutingTest {

  private static final S3Properties PROPERTIES = new S3Properties("test-originals", "test-variants",
      "https://test-variants.example/", "ap-northeast-2", Duration.ofMinutes(5), Duration.ofMinutes(5),
      "dummy", "dummy");

  private final AwsCredentialsProvider originals =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("ORIGINALS", "o-secret"));
  private final AwsCredentialsProvider variants =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("VARIANTS", "v-secret"));

  private final S3Client s3Client = mock(S3Client.class);
  private final S3Presigner s3Presigner = mock(S3Presigner.class);

  private S3StorageService service(S3CredentialRouting routing) {
    return new S3StorageService(s3Presigner, s3Client, PROPERTIES, routing);
  }

  private static Optional<IdentityProvider<?>> providerOf(AwsRequest request) {
    return request.overrideConfiguration().flatMap(AwsRequestOverrideConfiguration::credentialsIdentityProvider)
        .map(p -> (IdentityProvider<?>) p);
  }

  private void stubPresign() throws Exception {
    PresignedPutObjectRequest put = mock(PresignedPutObjectRequest.class);
    given(put.url()).willReturn(URI.create("https://example/put").toURL());
    given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(put);
    PresignedGetObjectRequest get = mock(PresignedGetObjectRequest.class);
    given(get.url()).willReturn(URI.create("https://example/get").toURL());
    given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).willReturn(get);
  }

  @Test
  @DisplayName("활성: originals 요청 5종은 originals 공급자, variants 요청은 variants 공급자를 쓴다")
  void enabled_routesPerBucket() throws Exception {
    stubPresign();
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willReturn(HeadObjectResponse.builder().contentType("image/jpeg").metadata(Map.of()).build());
    S3StorageService service = service(S3CredentialRouting.of(originals, variants));

    service.presignOriginalUpload("originals/1/a.jpg", "image/jpeg", 70);
    service.presignOriginalDownload("originals/1/a.jpg");
    service.findOriginal("originals/1/a.jpg");
    service.existsVariant("variants/1/a.webp");
    service.reprocessOriginal("originals/1/a.jpg", "originals/1/b.jpg", 80);

    ArgumentCaptor<PutObjectPresignRequest> put = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    verify(s3Presigner).presignPutObject(put.capture());
    assertThat(providerOf(put.getValue().putObjectRequest())).containsSame(originals);

    ArgumentCaptor<GetObjectPresignRequest> get = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(s3Presigner).presignGetObject(get.capture());
    assertThat(providerOf(get.getValue().getObjectRequest())).containsSame(originals);

    ArgumentCaptor<HeadObjectRequest> heads = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client, org.mockito.Mockito.times(3)).headObject(heads.capture());
    for (HeadObjectRequest head : heads.getAllValues()) {
      AwsCredentialsProvider expected = head.bucket().equals("test-variants") ? variants : originals;
      assertThat(providerOf(head)).as("HeadObject %s", head.bucket()).containsSame(expected);
    }

    ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
    verify(s3Client).copyObject(copy.capture());
    assertThat(providerOf(copy.getValue())).containsSame(originals);
  }

  @Test
  @DisplayName("비활성: 어떤 요청에도 자격증명 override를 붙이지 않는다")
  void disabled_noOverride() throws Exception {
    stubPresign();
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willReturn(HeadObjectResponse.builder().contentType("image/jpeg").metadata(Map.of()).build());
    S3StorageService service = service(S3CredentialRouting.disabled());

    service.presignOriginalUpload("originals/1/a.jpg", "image/jpeg", 70);
    service.presignOriginalDownload("originals/1/a.jpg");
    service.existsVariant("variants/1/a.webp");
    service.reprocessOriginal("originals/1/a.jpg", "originals/1/b.jpg", 80);

    ArgumentCaptor<PutObjectPresignRequest> put = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    verify(s3Presigner).presignPutObject(put.capture());
    assertThat(put.getValue().putObjectRequest().overrideConfiguration()).isEmpty();

    ArgumentCaptor<GetObjectPresignRequest> get = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(s3Presigner).presignGetObject(get.capture());
    assertThat(get.getValue().getObjectRequest().overrideConfiguration()).isEmpty();

    ArgumentCaptor<HeadObjectRequest> heads = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client, org.mockito.Mockito.times(2)).headObject(heads.capture());
    assertThat(heads.getAllValues()).allSatisfy(h -> assertThat(h.overrideConfiguration()).isEmpty());

    ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
    verify(s3Client).copyObject(copy.capture());
    assertThat(copy.getValue().overrideConfiguration()).isEmpty();
  }
}
