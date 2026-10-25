package com.blursome.blursome.global.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 Presigned URL 발급·블러본 존재 확인·블러 재처리용 원본 복사를 담당하는 스토리지 서비스. 버킷·메타데이터 규칙 등 S3 세부를 캡슐화해,
 * 도메인 서비스는 key/콘텐츠 타입/블러 강도만 다루도록 한다.
 *
 * <p>Presigned URL 발급은 AWS 호출 없는 서명 계산이라 추가 비용이 없다.
 * (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §5)
 */
@Service
@RequiredArgsConstructor
public class S3StorageService {

  /**
   * 원본 객체에 박는 블러 강도 메타데이터 키. SDK가 {@code metadata("blur-level", ...)}로 넣으면 실제 객체에는
   * {@code x-amz-meta-blur-level}로 저장되고, Lambda는 SDK로 {@code blur-level} 키로 읽는다.
   */
  static final String BLUR_LEVEL_METADATA_KEY = "blur-level";
  private static final String METADATA_HEADER_PREFIX = "x-amz-meta-";
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final int HTTP_NOT_FOUND = 404;

  private final S3Presigner s3Presigner;
  private final S3Client s3Client;
  private final S3Properties properties;

  /**
   * 비공개 originals 버킷에 대한 Presigned PUT URL을 발급한다. {@code blurLevel}을 원본 객체 메타데이터로
   * 서명에 고정하므로, 프론트가 값을 임의로 바꿔 PUT하면 서명이 깨진다.
   *
   * @param objectKey originals 버킷의 원본 객체 key
   * @param contentType 업로드할 이미지 MIME 타입(서명에 고정됨)
   * @param blurLevel 블러 강도(50~100). {@code x-amz-meta-blur-level}로 박힌다.
   * @return 발급 URL과 프론트가 반드시 함께 보내야 하는 헤더 맵
   */
  public PresignedUpload presignOriginalUpload(String objectKey, String contentType, int blurLevel) {
    String blurLevelValue = String.valueOf(blurLevel);

    PutObjectRequest objectRequest = PutObjectRequest.builder()
        .bucket(properties.originalsBucket())
        .key(objectKey)
        .contentType(contentType)
        .metadata(Map.of(BLUR_LEVEL_METADATA_KEY, blurLevelValue))
        .build();

    PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
        PutObjectPresignRequest.builder()
            .signatureDuration(properties.putExpiration())
            .putObjectRequest(objectRequest)
            .build());

    // 서명에 고정된 헤더를 프론트가 그대로 실어 보내야 PUT이 성공한다(누락 시 서명 불일치).
    Map<String, String> requiredHeaders = new LinkedHashMap<>();
    requiredHeaders.put(CONTENT_TYPE_HEADER, contentType);
    requiredHeaders.put(METADATA_HEADER_PREFIX + BLUR_LEVEL_METADATA_KEY, blurLevelValue);

    return new PresignedUpload(presigned.url().toString(), requiredHeaders);
  }

  /**
   * 비공개 originals 버킷의 원본 객체에 대한 단기 Presigned GET URL을 발급한다. 채팅 단계 조건을 충족한
   * 참여자에게만 원본을 전달하기 위한 경로로, 만료({@code getExpiration}, 기본 5분)가 지나면 접근이 막힌다.
   * 버킷이 완전 비공개여도 IAM 자격증명 서명으로 일정 시간만 GET을 허용한다.
   *
   * <p>발급 자체는 AWS 호출 없는 서명 계산이라 추가 비용이 없다. 채팅 단계 전환당 드물게 일어나는 이벤트라
   * CloudFront 캐싱 이득이 작아 Presigned GET을 쓴다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §5·§6)
   *
   * @param objectKey originals 버킷의 원본 객체 key
   * @return 만료 전까지 직접 GET할 수 있는 단기 URL
   */
  public String presignOriginalDownload(String objectKey) {
    GetObjectRequest objectRequest = GetObjectRequest.builder()
        .bucket(properties.originalsBucket())
        .key(objectKey)
        .build();

    PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
        GetObjectPresignRequest.builder()
            .signatureDuration(properties.getExpiration())
            .getObjectRequest(objectRequest)
            .build());

    return presigned.url().toString();
  }

  /**
   * 공개 variants 버킷의 블러본 key를 공개 접근 URL로 조립한다. 블러본은 공개 GET이 허용되므로 별도 서명
   * 없이 {@code variantsBaseUrl + variantKey}로 접근할 수 있다. (설계: {@code FEED_IMAGE_DOMAIN.md} §2-1)
   *
   * @param variantKey variants 버킷의 블러본 객체 key
   * @return 블러본 공개 URL
   */
  public String toPublicVariantUrl(String variantKey) {
    return properties.variantsBaseUrl() + variantKey;
  }

  /**
   * variants 버킷에 블러본 객체가 존재하는지 {@code HeadObject}로 확인한다. Lambda가 비동기로 생성하는
   * 블러본의 실제 존재 여부를 백엔드 스케줄러(#51)가 폴링하는 데 사용한다.
   *
   * <p>객체가 없으면(HTTP 404) {@code false}를 반환한다. 그 외 S3 오류(권한·일시 장애 등)는 "존재하지 않음"과
   * 구분되어야 하므로 예외를 그대로 전파해, 호출자가 시간 기반 {@code FAILED} 판정과 분리해 다룰 수 있게 한다.
   *
   * <p><strong>IAM 전제</strong>: 부재 객체가 404로 돌아오려면 이 클라이언트의 자격증명에 variants 버킷
   * {@code s3:ListBucket} 권한이 있어야 한다. 없으면 AWS가 부재 객체를 403으로 가리고, 그러면 스케줄러(#51)가
   * 이를 일시 오류로 보아 상태를 바꾸지 않아 해당 이미지가 영원히 {@code PROCESSING}에 고착된다. (운영 IAM 정책:
   * {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-5)
   *
   * @param variantKey variants 버킷의 블러본 객체 key
   * @return 객체가 존재하면 {@code true}, 404면 {@code false}
   * @throws S3Exception 404가 아닌 S3 오류
   */
  public boolean existsVariant(String variantKey) {
    try {
      s3Client.headObject(HeadObjectRequest.builder()
          .bucket(properties.variantsBucket())
          .key(variantKey)
          .build());
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw e;
    }
  }

  /**
   * originals 버킷의 원본 객체를 {@code HeadObject}로 조회해 블러 Lambda가 읽을 {@code blur-level} 메타데이터를 돌려준다.
   * 저장 API가 새 사진의 요청 블러 강도를 실제로 업로드된 값과 대조하는 데 쓴다(#105). 객체 바이트는 받지 않는다.
   *
   * @param objectKey originals 버킷의 원본 객체 key
   * @return 객체가 있으면 메타데이터 정보, 없으면(HTTP 404) 빈 값
   * @throws S3Exception 404가 아닌 S3 오류
   */
  public Optional<OriginalObject> findOriginal(String objectKey) {
    try {
      HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
          .bucket(properties.originalsBucket())
          .key(objectKey)
          .build());
      return Optional.of(new OriginalObject(head.metadata().get(BLUR_LEVEL_METADATA_KEY)));
    } catch (S3Exception e) {
      if (e.statusCode() == HTTP_NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * 블러 강도를 바꿔 블러본을 다시 만들도록 원본을 새 key로 복사한다(#105 copy-on-reprocess).
   *
   * <p>originals 버킷 안에서 server-side {@code CopyObject}만 수행하므로 원본 바이트가 애플리케이션 서버를 거치지
   * 않는다. 메타데이터 지시자를 {@code REPLACE}로 두고 {@code blur-level}을 새 값으로 기록하며, REPLACE는
   * Content-Type까지 초기화하므로 먼저 {@code HeadObject}로 기존 Content-Type·사용자 메타데이터를 읽어 그대로
   * 옮긴다. 새 객체의 {@code ObjectCreated:Copy} 이벤트가 블러 Lambda를 다시 실행한다(알림 이벤트가
   * {@code s3:ObjectCreated:*}여야 한다).
   *
   * <p>기존 원본은 덮어쓰거나 삭제하지 않는다. 재처리가 실패해도 이전 객체가 남고, 이후 고아 객체 cleanup 대상이 된다.
   *
   * @param sourceKey 복사할 기존 원본 key
   * @param targetKey 새 원본 key
   * @param blurLevel 새 블러 강도(50~100). {@code x-amz-meta-blur-level}로 기록된다.
   * @throws software.amazon.awssdk.core.exception.SdkException S3 조회·복사 실패
   */
  public void reprocessOriginal(String sourceKey, String targetKey, int blurLevel) {
    String bucket = properties.originalsBucket();
    HeadObjectResponse source = s3Client.headObject(HeadObjectRequest.builder()
        .bucket(bucket)
        .key(sourceKey)
        .build());

    Map<String, String> metadata = new LinkedHashMap<>(source.metadata());
    metadata.put(BLUR_LEVEL_METADATA_KEY, String.valueOf(blurLevel));

    s3Client.copyObject(CopyObjectRequest.builder()
        .sourceBucket(bucket)
        .sourceKey(sourceKey)
        .destinationBucket(bucket)
        .destinationKey(targetKey)
        .metadataDirective(MetadataDirective.REPLACE)
        .contentType(source.contentType())
        .metadata(metadata)
        .build());
  }

  /**
   * 업로드된 원본 객체의 메타데이터.
   *
   * @param blurLevelMetadata {@code x-amz-meta-blur-level} 원문. 메타데이터가 없으면 {@code null}
   */
  public record OriginalObject(String blurLevelMetadata) {
  }

  /**
   * Presigned PUT 발급 결과.
   *
   * @param uploadUrl 프론트가 직접 PUT할 단기 URL
   * @param requiredHeaders PUT 시 반드시 함께 보내야 하는 헤더 맵
   */
  public record PresignedUpload(String uploadUrl, Map<String, String> requiredHeaders) {
  }
}
