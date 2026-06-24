package com.blursome.blursome.global.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 Presigned URL 발급 책임을 가지는 스토리지 서비스. 버킷·메타데이터 규칙 등 S3 세부를 캡슐화해,
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

  private final S3Presigner s3Presigner;
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
   * Presigned PUT 발급 결과.
   *
   * @param uploadUrl 프론트가 직접 PUT할 단기 URL
   * @param requiredHeaders PUT 시 반드시 함께 보내야 하는 헤더 맵
   */
  public record PresignedUpload(String uploadUrl, Map<String, String> requiredHeaders) {
  }
}
