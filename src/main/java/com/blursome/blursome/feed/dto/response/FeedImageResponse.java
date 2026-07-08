package com.blursome.blursome.feed.dto.response;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import java.util.List;

/**
 * 피드 이미지 메타데이터 저장(full-replace) 결과. 저장된 사진을 노출 순서대로 담는다.
 *
 * <p>본인 피드 관리 관점이므로 {@code PROCESSING}/{@code FAILED} 상태도 그대로 내려준다. 블러본은 Lambda가
 * 비동기로 생성하므로 저장 직후에는 {@link FeedImageProcessingStatus#PROCESSING}이며, 이때 {@code variantUrl}이
 * 가리키는 객체는 아직 S3에 없을 수 있다. (설계: {@code FEED_IMAGE_DOMAIN.md} §2-5)
 */
public record FeedImageResponse(List<Image> images) {

  /**
   * 저장된 사진 1장.
   *
   * @param id 저장된 {@link FeedImage}의 PK
   * @param displayOrder 피드 내 노출 순서(1~5)
   * @param variantUrl 공개 블러본 URL({@code variantsBaseUrl + variantKey})
   * @param blurLevel 블러 강도(50~100)
   * @param processingStatus 블러본 생성 상태({@code PROCESSING} → {@code READY}/{@code FAILED})
   */
  public record Image(
      Long id,
      Integer displayOrder,
      String variantUrl,
      Integer blurLevel,
      FeedImageProcessingStatus processingStatus
  ) {

    public static Image of(FeedImage image, String variantUrl) {
      return new Image(
          image.getId(),
          image.getDisplayOrder(),
          variantUrl,
          image.getBlurLevel(),
          image.getProcessingStatus());
    }
  }
}
