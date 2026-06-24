package com.blursome.blursome.feed.service;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse.PresignedUrl;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 피드 이미지 도메인 서비스. 현재는 원본 업로드용 Presigned PUT URL 발급을 담당한다.
 *
 * <p>발급은 DB를 거치지 않는다. 회원 id로 결정론적 원본 key를 만들고({@link S3ObjectKeyGenerator}),
 * blur_level을 메타데이터로 박은 Presigned PUT을 발급한다({@link S3StorageService}). 실제 {@link FeedImage}
 * 행은 프론트가 S3 업로드를 마친 뒤 #50 메타데이터 저장 API에서 생성된다.
 */
@Service
@RequiredArgsConstructor
public class FeedImageService {

  private final S3ObjectKeyGenerator keyGenerator;
  private final S3StorageService storageService;

  /**
   * 요청한 사진들에 대해 originals 버킷 Presigned PUT URL을 사진 순서대로 발급한다.
   *
   * @param memberId 업로드 주체(인증된 회원). 원본 key prefix에 사용된다.
   * @param request 사진별 파일명·콘텐츠 타입·블러 강도
   * @return 사진별 {@code uploadUrl}·{@code originalKey}·{@code requiredHeaders}
   */
  public PresignedUrlResponse issuePresignedUploadUrls(Long memberId, PresignedUrlRequest request) {
    List<PresignedUrl> images = request.images().stream()
        .map(image -> issueOne(memberId, image))
        .toList();
    return new PresignedUrlResponse(images);
  }

  private PresignedUrl issueOne(Long memberId, ImageRequest image) {
    int blurLevel = image.blurLevel() != null ? image.blurLevel() : FeedImage.DEFAULT_BLUR_LEVEL;
    String originalKey = keyGenerator.generateOriginalKey(memberId, extractExtension(image.fileName()));
    PresignedUpload upload =
        storageService.presignOriginalUpload(originalKey, image.contentType(), blurLevel);
    return new PresignedUrl(upload.uploadUrl(), originalKey, upload.requiredHeaders());
  }

  /** 파일명에서 확장자(점 제외)를 추출한다. DTO 검증이 확장자 존재를 보장하지만 마지막 방어선으로 한 번 더 확인한다. */
  private String extractExtension(String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    if (lastDot < 0 || lastDot == fileName.length() - 1) {
      throw new IllegalArgumentException("파일명에 확장자가 없습니다: " + fileName);
    }
    return fileName.substring(lastDot + 1);
  }
}
