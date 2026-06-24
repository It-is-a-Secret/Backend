package com.blursome.blursome.feed.dto.request;

import com.blursome.blursome.feed.domain.FeedImage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 피드 이미지 메타데이터 저장(full-replace) 요청. 프론트가 원본 S3 업로드를 마친 뒤, 발급받은 원본 key
 * ({@link Image#objectKey()})와 노출 순서·블러 강도를 담아 보낸다. 요청 목록은 해당 피드 사진의
 * <b>전체 집합</b>으로 간주되어 기존 이미지를 대체한다.
 *
 * <p><b>빈 목록 정책</b>: 저장 요청은 최소 1장을 강제한다({@code @Size(min = 1)}). 0장(전체 삭제)은
 * v1에서 지원하지 않으며, 필요해지면 별도 delete 엔드포인트로 다룬다.
 * (설계: {@code FEED_IMAGE_DOMAIN.md} §4, {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-2·§8-3)
 */
public record FeedImageSaveRequest(
    @NotEmpty(message = "피드 이미지는 1장 이상 필요합니다.")
    @Size(
        min = FeedImage.MIN_DISPLAY_ORDER,
        max = FeedImage.MAX_DISPLAY_ORDER,
        message = "피드 이미지는 1장 이상 5장 이하로 저장할 수 있습니다.")
    @Valid
    List<Image> images
) {

  /**
   * 저장할 사진 1장의 메타데이터.
   *
   * @param objectKey #49 Presigned 발급 응답의 {@code originalKey}(비공개 originals 버킷 원본 객체 key).
   *     블러본 {@code variantKey}는 이 값에서 결정론적으로 계산된다.
   * @param displayOrder 피드 내 노출 순서(1~5, 1-base). 한 요청 내 중복은 서비스에서 검증한다.
   * @param blurLevel 블러 강도(50~100). 원본 업로드 시 메타데이터로 박은 값과 동일해야 하며, 생략 시
   *     {@link FeedImage#DEFAULT_BLUR_LEVEL}이 적용된다.
   */
  public record Image(
      @NotBlank(message = "원본 객체 key는 필수입니다.")
      @Size(max = 512, message = "원본 객체 key는 512자 이하여야 합니다.")
      String objectKey,

      @NotNull(message = "노출 순서는 필수입니다.")
      @Min(value = FeedImage.MIN_DISPLAY_ORDER, message = "노출 순서는 1 이상이어야 합니다.")
      @Max(value = FeedImage.MAX_DISPLAY_ORDER, message = "노출 순서는 5 이하여야 합니다.")
      Integer displayOrder,

      @Min(value = FeedImage.MIN_BLUR_LEVEL, message = "블러 강도는 50 이상이어야 합니다.")
      @Max(value = FeedImage.MAX_BLUR_LEVEL, message = "블러 강도는 100 이하여야 합니다.")
      Integer blurLevel
  ) {
  }
}
