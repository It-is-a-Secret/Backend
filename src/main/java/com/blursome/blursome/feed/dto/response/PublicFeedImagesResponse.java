package com.blursome.blursome.feed.dto.response;

import com.blursome.blursome.feed.domain.FeedImage;
import java.util.List;

/**
 * 공개 피드 이미지 조회 응답(다른 회원이 보는 피드). 모든 이미지가 {@code READY}일 때만 노출되는
 * 전부-또는-비노출 게이트를 통과한 결과이므로, 깨진/처리중 블러본은 담기지 않는다. 피드에는 블러본만
 * 나가므로 공개 블러본 URL과 노출 순서만 내려준다. (설계: {@code FEED_IMAGE_DOMAIN.md} §2-5)
 */
public record PublicFeedImagesResponse(List<Image> images) {

  /**
   * 공개 노출되는 사진 1장.
   *
   * @param displayOrder 피드 내 노출 순서(1~5)
   * @param variantUrl 공개 블러본 URL({@code variantsBaseUrl + variantKey})
   */
  public record Image(Integer displayOrder, String variantUrl) {

    public static Image of(FeedImage image, String variantUrl) {
      return new Image(image.getDisplayOrder(), variantUrl);
    }
  }
}
