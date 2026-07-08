package com.blursome.blursome.feed.dto.request;

import com.blursome.blursome.feed.domain.FeedImage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 피드 이미지 원본 업로드용 Presigned PUT URL 발급 요청. 한 번에 여러 장(최대 {@value FeedImage#MAX_DISPLAY_ORDER}장)을
 * 요청할 수 있고, 사진마다 파일명·콘텐츠 타입·블러 강도를 따로 지정한다.
 *
 * <p>{@code blurLevel}은 프론트가 사진별로 지정하는 Lambda 블러 강도값이며, 백엔드가 원본 객체 메타데이터
 * ({@code x-amz-meta-blur-level})로 서명에 고정한다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §2)
 */
public record PresignedUrlRequest(
    @NotEmpty(message = "이미지 정보는 하나 이상 필요합니다.")
    @Size(max = FeedImage.MAX_DISPLAY_ORDER, message = "피드 이미지는 최대 5장까지 업로드할 수 있습니다.")
    @Valid
    List<ImageRequest> images
) {

  /**
   * 사진 1장의 업로드 정보.
   *
   * @param fileName 원본 파일명(확장자 포함). 원본 객체 key의 확장자 결정에 사용한다.
   * @param contentType MIME 타입. {@code image/*}만 허용한다. Presigned PUT의 {@code Content-Type}으로 서명에 고정된다.
   * @param blurLevel 블러 강도(50~100). 생략 시 {@link FeedImage#DEFAULT_BLUR_LEVEL}이 적용된다.
   */
  public record ImageRequest(
      @NotBlank(message = "파일명은 필수입니다.")
      @Size(max = 255, message = "파일명은 255자 이하여야 합니다.")
      @Pattern(regexp = ".+\\.[A-Za-z0-9]+", message = "파일명에 확장자가 있어야 합니다.")
      String fileName,

      @NotBlank(message = "콘텐츠 타입은 필수입니다.")
      @Pattern(regexp = "image/.+", message = "이미지(image/*) 파일만 업로드할 수 있습니다.")
      String contentType,

      @Min(value = FeedImage.MIN_BLUR_LEVEL, message = "블러 강도는 50 이상이어야 합니다.")
      @Max(value = FeedImage.MAX_BLUR_LEVEL, message = "블러 강도는 100 이하여야 합니다.")
      Integer blurLevel
  ) {
  }
}
