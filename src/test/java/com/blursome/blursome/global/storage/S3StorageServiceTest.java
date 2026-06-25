package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

  private static final String ORIGINALS_BUCKET = "blursome-originals-test";
  private static final String VARIANTS_BUCKET = "blursome-variants-test";
  private static final Duration PUT_EXPIRATION = Duration.ofMinutes(5);
  private static final Duration GET_EXPIRATION = Duration.ofMinutes(5);
  private static final String OBJECT_KEY = "originals/100/abc.jpg";
  private static final String VARIANT_KEY = "variants/100/abc.webp";
  private static final String CONTENT_TYPE = "image/jpeg";
  private static final int BLUR_LEVEL = 70;

  @Mock
  private S3Presigner s3Presigner;

  @Mock
  private S3Client s3Client;

  @Mock
  private S3Properties properties;

  @InjectMocks
  private S3StorageService s3StorageService;

  @Captor
  private ArgumentCaptor<PutObjectPresignRequest> presignRequestCaptor;

  @Captor
  private ArgumentCaptor<GetObjectPresignRequest> getPresignRequestCaptor;

  @Test
  @DisplayName("originals 버킷 대상으로 blur-level 메타데이터를 박아 Presigned PUT을 발급한다")
  void presignOriginalUpload() {
    given(properties.originalsBucket()).willReturn(ORIGINALS_BUCKET);
    given(properties.putExpiration()).willReturn(PUT_EXPIRATION);
    given(s3Presigner.presignPutObject(presignRequestCaptor.capture()))
        .willReturn(presignedWithUrl("https://" + ORIGINALS_BUCKET + ".s3.amazonaws.com/" + OBJECT_KEY));

    PresignedUpload result =
        s3StorageService.presignOriginalUpload(OBJECT_KEY, CONTENT_TYPE, BLUR_LEVEL);

    // 서명에 들어간 PutObjectRequest 검증: originals 버킷·key·contentType·blur-level 메타데이터
    PutObjectRequest signedRequest = presignRequestCaptor.getValue().putObjectRequest();
    assertThat(signedRequest.bucket()).isEqualTo(ORIGINALS_BUCKET);
    assertThat(signedRequest.key()).isEqualTo(OBJECT_KEY);
    assertThat(signedRequest.contentType()).isEqualTo(CONTENT_TYPE);
    assertThat(signedRequest.metadata()).containsEntry("blur-level", "70");
    assertThat(presignRequestCaptor.getValue().signatureDuration()).isEqualTo(PUT_EXPIRATION);

    // 응답: uploadUrl + 프론트가 그대로 보내야 하는 requiredHeaders
    assertThat(result.uploadUrl()).contains(ORIGINALS_BUCKET).contains(OBJECT_KEY);
    assertThat(result.requiredHeaders())
        .containsEntry("Content-Type", CONTENT_TYPE)
        .containsEntry("x-amz-meta-blur-level", "70");
  }

  @Test
  @DisplayName("presignOriginalDownload는 originals 버킷 대상으로 getExpiration 만료의 Presigned GET을 발급한다")
  void presignOriginalDownload() {
    given(properties.originalsBucket()).willReturn(ORIGINALS_BUCKET);
    given(properties.getExpiration()).willReturn(GET_EXPIRATION);
    given(s3Presigner.presignGetObject(getPresignRequestCaptor.capture()))
        .willReturn(presignedGetWithUrl(
            "https://" + ORIGINALS_BUCKET + ".s3.amazonaws.com/" + OBJECT_KEY));

    String url = s3StorageService.presignOriginalDownload(OBJECT_KEY);

    // 서명에 들어간 GetObjectRequest 검증: originals 버킷·key·만료(getExpiration)
    GetObjectRequest signedRequest = getPresignRequestCaptor.getValue().getObjectRequest();
    assertThat(signedRequest.bucket()).isEqualTo(ORIGINALS_BUCKET);
    assertThat(signedRequest.key()).isEqualTo(OBJECT_KEY);
    assertThat(getPresignRequestCaptor.getValue().signatureDuration()).isEqualTo(GET_EXPIRATION);
    assertThat(url).contains(ORIGINALS_BUCKET).contains(OBJECT_KEY);
  }

  @Test
  @DisplayName("existsVariant는 variants 버킷 HeadObject가 성공하면 true를 반환한다")
  void existsVariant_returnsTrue_whenHeadObjectSucceeds() {
    given(properties.variantsBucket()).willReturn(VARIANTS_BUCKET);
    ArgumentCaptor<HeadObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(HeadObjectRequest.class);
    given(s3Client.headObject(requestCaptor.capture()))
        .willReturn(HeadObjectResponse.builder().build());

    boolean exists = s3StorageService.existsVariant(VARIANT_KEY);

    assertThat(exists).isTrue();
    assertThat(requestCaptor.getValue().bucket()).isEqualTo(VARIANTS_BUCKET);
    assertThat(requestCaptor.getValue().key()).isEqualTo(VARIANT_KEY);
  }

  @Test
  @DisplayName("existsVariant는 HeadObject가 404면 false를 반환한다")
  void existsVariant_returnsFalse_whenNotFound() {
    given(properties.variantsBucket()).willReturn(VARIANTS_BUCKET);
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willThrow((S3Exception) S3Exception.builder().statusCode(404).build());

    boolean exists = s3StorageService.existsVariant(VARIANT_KEY);

    assertThat(exists).isFalse();
  }

  @Test
  @DisplayName("existsVariant는 404가 아닌 S3 오류는 그대로 전파한다")
  void existsVariant_propagates_whenOtherS3Error() {
    given(properties.variantsBucket()).willReturn(VARIANTS_BUCKET);
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willThrow((S3Exception) S3Exception.builder().statusCode(403).build());

    assertThatThrownBy(() -> s3StorageService.existsVariant(VARIANT_KEY))
        .isInstanceOf(S3Exception.class);
  }

  private PresignedPutObjectRequest presignedWithUrl(String url) {
    SdkHttpFullRequest httpRequest = SdkHttpFullRequest.builder()
        .method(SdkHttpMethod.PUT)
        .uri(URI.create(url))
        .build();
    return PresignedPutObjectRequest.builder()
        .expiration(Instant.now().plus(PUT_EXPIRATION))
        .isBrowserExecutable(false)
        .signedHeaders(Map.of("host", List.of(URI.create(url).getHost())))
        .httpRequest(httpRequest)
        .build();
  }

  private PresignedGetObjectRequest presignedGetWithUrl(String url) {
    SdkHttpFullRequest httpRequest = SdkHttpFullRequest.builder()
        .method(SdkHttpMethod.GET)
        .uri(URI.create(url))
        .build();
    return PresignedGetObjectRequest.builder()
        .expiration(Instant.now().plus(GET_EXPIRATION))
        .isBrowserExecutable(true)
        .signedHeaders(Map.of("host", List.of(URI.create(url).getHost())))
        .httpRequest(httpRequest)
        .build();
  }
}
