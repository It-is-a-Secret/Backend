package com.blursome.blursome.feed.dto.response;

import com.blursome.blursome.feed.domain.FeedImage;
import java.util.List;

/**
 * 채팅 단계별 원본 공개 조회 응답. 채팅방의 진행 단계가 오를수록 {@code displayOrder} 순서대로 원본을 1장씩
 * 공개하므로, 공개 장수 N 이하의 사진은 비공개 원본 단기 Presigned GET URL을, 나머지(N+1..)는 공개 블러본
 * URL을 담는다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §3·§4, ④-b)
 *
 * <p>원본/블러본 key·버킷 구조는 feed 도메인이 전담하며, 응답에는 발급된 URL만 담아 호출 측(chat)이 key
 * 구조를 모른 채 "장수"만 다루도록 한다.
 */
public record RevealedFeedImagesResponse(List<Image> images) {

  /**
   * 채팅에서 보이는 사진 1장.
   *
   * @param displayOrder 피드 내 노출 순서(1~5)
   * @param revealed 원본 공개 여부. {@code true}면 {@code imageUrl}이 원본 단기 Presigned GET URL,
   *     {@code false}면 공개 블러본 URL이다.
   * @param imageUrl {@code revealed}에 따라 원본 Presigned GET URL 또는 블러본 공개 URL
   */
  public record Image(Integer displayOrder, boolean revealed, String imageUrl) {

    /** 원본이 공개된 사진(원본 단기 Presigned GET URL). */
    public static Image revealed(FeedImage image, String originalUrl) {
      return new Image(image.getDisplayOrder(), true, originalUrl);
    }

    /** 아직 공개되지 않아 블러본으로 보이는 사진(블러본 공개 URL). */
    public static Image blurred(FeedImage image, String variantUrl) {
      return new Image(image.getDisplayOrder(), false, variantUrl);
    }
  }
}
