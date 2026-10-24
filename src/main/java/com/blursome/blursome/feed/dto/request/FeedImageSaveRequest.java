package com.blursome.blursome.feed.dto.request;

import com.blursome.blursome.feed.domain.FeedImage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 피드 이미지 저장(full-replace) 요청. 요청 목록은 해당 피드 사진의 <b>최종 전체 집합</b>으로 간주된다.
 *
 * <p>각 항목은 기존 사진({@link Image#id()})과 새로 업로드한 사진({@link Image#objectKey()}) 중
 * <b>정확히 하나</b>로 식별한다(#105). 기존 사진은 {@code GET /api/feeds/me/images} 응답의 {@code id}를 그대로
 * 보내면 되고, 원본 key는 응답에 노출하지 않는다. 현재 피드에 있지만 요청에 빠진 사진은 삭제된다.
 *
 * <p><b>빈 목록 정책</b>: 저장 요청은 최소 1장을 강제한다({@code @Size(min = 1)}). 0장(전체 삭제)은
 * 지원하지 않으며, 필요해지면 별도 delete 엔드포인트로 다룬다.
 * (설계: {@code FEED_IMAGE_DOMAIN.md} §4, {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-2·§8-3)
 */
@Schema(description = "피드 사진 저장 요청. images는 저장 후 피드에 남을 사진의 전체 목록이다.",
    example = """
        {"images":[
          {"id":10,"displayOrder":1},
          {"id":12,"displayOrder":2,"blurLevel":90},
          {"objectKey":"originals/100/11111111-1111-1111-1111-111111111111.png","displayOrder":3,"blurLevel":80}
        ]}""")
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
   * 저장할 사진 1장. {@code id}와 {@code objectKey} 중 정확히 하나만 보내야 한다. 이 조합 규칙과 요청 내
   * 중복(id·objectKey·displayOrder)은 도메인 오류 코드로 응답하기 위해 서비스에서 검증한다.
   *
   * @param id 유지할 기존 사진의 {@link FeedImage} id(관리 조회 응답의 {@code id}). 새 사진이면 생략한다.
   * @param objectKey 새 사진의 원본 key(#49 Presigned 발급 응답의 {@code originalKey}). 기존 사진이면 생략한다.
   * @param displayOrder 피드 내 노출 순서(1~5, 1-base). 한 요청 내 중복은 서비스에서 검증한다.
   * @param blurLevel 블러 강도(50~100). 기존 사진에서 생략하면 현재 값을 유지하고, 현재 값과 다르면 서버가
   *     블러본을 다시 만든다. 새 사진에서 생략하면 {@link FeedImage#DEFAULT_BLUR_LEVEL}이 적용되며, Presigned
   *     발급 때 지정한 값과 같아야 한다.
   */
  @Schema(name = "FeedImageSaveItem",
      description = "피드 사진 저장 요청의 사진 한 장. 기존 사진은 id, 새 사진은 objectKey 중 정확히 하나로 식별한다.")
  public record Image(
      @Schema(description = "유지할 기존 사진 id(GET /api/feeds/me/images 응답의 id). objectKey와 함께 보낼 수 없다.",
          example = "10")
      @Positive(message = "사진 id는 양수여야 합니다.")
      Long id,

      @Schema(description = "새로 업로드한 사진의 원본 key(Presigned 발급 응답의 originalKey). id와 함께 보낼 수 없다.",
          example = "originals/100/11111111-1111-1111-1111-111111111111.png")
      @Size(max = 512, message = "원본 객체 key는 512자 이하여야 합니다.")
      String objectKey,

      @Schema(description = "노출 순서(1~5). 요청 안에서 중복될 수 없다.", example = "1")
      @NotNull(message = "노출 순서는 필수입니다.")
      @Min(value = FeedImage.MIN_DISPLAY_ORDER, message = "노출 순서는 1 이상이어야 합니다.")
      @Max(value = FeedImage.MAX_DISPLAY_ORDER, message = "노출 순서는 5 이하여야 합니다.")
      Integer displayOrder,

      @Schema(description = "블러 강도(50~100). 기존 사진은 생략 시 현재 값 유지(바뀌면 서버가 블러본 재생성), "
          + "새 사진은 생략 시 80.", example = "80")
      @Min(value = FeedImage.MIN_BLUR_LEVEL, message = "블러 강도는 50 이상이어야 합니다.")
      @Max(value = FeedImage.MAX_BLUR_LEVEL, message = "블러 강도는 100 이하여야 합니다.")
      Integer blurLevel
  ) {

    /** 기존 사진을 id로 참조하는 항목을 만든다. */
    public static Image existing(Long id, Integer displayOrder, Integer blurLevel) {
      return new Image(id, null, displayOrder, blurLevel);
    }

    /** 새로 업로드한 사진을 원본 key로 참조하는 항목을 만든다. */
    public static Image upload(String objectKey, Integer displayOrder, Integer blurLevel) {
      return new Image(null, objectKey, displayOrder, blurLevel);
    }

    /** 기존 사진을 가리키는 항목인지 여부. {@link #hasSingleReference()}가 참일 때만 의미가 있다. */
    @JsonIgnore
    public boolean isExisting() {
      return id != null;
    }

    /** {@code id}와 {@code objectKey} 중 정확히 하나만 있는지 여부. 빈 문자열 key는 없는 것으로 본다. */
    @JsonIgnore
    public boolean hasSingleReference() {
      boolean hasObjectKey = objectKey != null && !objectKey.isBlank();
      return (id != null) != hasObjectKey;
    }
  }
}
