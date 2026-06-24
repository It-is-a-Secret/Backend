package com.blursome.blursome.feed.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 피드에 노출되는 사진 1장. {@link Feed}와 N:1로 대응한다.
 *
 * <p>사진 바이너리는 백엔드를 거치지 않고 프론트엔드가 S3 Presigned URL로 직접 업로드한다. 본 엔티티는
 * S3 객체 key(비공개 원본·공개 블러본)와 노출 순서·블러 강도·블러본 생성 상태만 보관한다.
 *
 * <p>모든 컬럼은 {@code nullable=false}로 두어 행이 존재하면 항상 완전한 정보를 갖도록 한다.
 * {@code variantKey}가 non-null이라고 해서 블러본 객체가 S3에 존재함을 보장하지는 않으며, 실제 존재는
 * {@link FeedImageProcessingStatus}로 추적한다.
 */
@Entity
@Getter
@Table(
    name = "feed_image",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_feed_image_order",
            columnNames = {"feed_id", "display_order"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedImage extends BaseEntity {

  /** 블러 강도 기본값. 프론트가 지정하지 않으면 적용된다. */
  public static final int DEFAULT_BLUR_LEVEL = 80;

  /** 피드 내 노출 순서 하한(1-base). */
  public static final int MIN_DISPLAY_ORDER = 1;
  /** 피드당 사진 최대 장수이자 노출 순서 상한. */
  public static final int MAX_DISPLAY_ORDER = 5;
  /** 블러 강도 하한. 약하게 굽는 것을 막기 위한 최소값. */
  public static final int MIN_BLUR_LEVEL = 50;
  /** 블러 강도 상한. */
  public static final int MAX_BLUR_LEVEL = 100;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "feed_id", nullable = false)
  private Feed feed;

  @Column(name = "display_order", nullable = false)
  private Integer displayOrder;

  @Column(name = "original_key", nullable = false, length = 512)
  private String originalKey;

  @Column(name = "variant_key", nullable = false, length = 512)
  private String variantKey;

  @Column(name = "blur_level", nullable = false)
  private Integer blurLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "processing_status", nullable = false, length = 20)
  private FeedImageProcessingStatus processingStatus;

  @Builder(access = AccessLevel.PRIVATE)
  private FeedImage(Feed feed, Integer displayOrder, String originalKey, String variantKey,
      Integer blurLevel, FeedImageProcessingStatus processingStatus) {
    this.feed = feed;
    this.displayOrder = displayOrder;
    this.originalKey = originalKey;
    this.variantKey = variantKey;
    this.blurLevel = blurLevel;
    this.processingStatus = processingStatus;
  }

  /**
   * 메타데이터 저장 시 피드 이미지를 생성한다. 블러본은 Lambda가 비동기로 생성하므로 상태는
   * {@link FeedImageProcessingStatus#PROCESSING}으로 시작한다. {@code blurLevel}이 {@code null}이면
   * {@link #DEFAULT_BLUR_LEVEL}을 적용한다.
   *
   * <p>순서·블러 강도·장수 범위의 1차 검증은 요청 DTO(Bean Validation)와 Service 레벨에서 담당하지만,
   * 본 팩토리도 불완전한 상태의 객체가 생성되지 못하도록 마지막 방어선으로 불변식을 직접 강제한다.
   *
   * @throws IllegalArgumentException feed/key가 비어 있거나 순서·블러 강도가 허용 범위를 벗어난 경우
   */
  public static FeedImage create(Feed feed, Integer displayOrder, String originalKey,
      String variantKey, Integer blurLevel) {
    int resolvedBlurLevel = blurLevel != null ? blurLevel : DEFAULT_BLUR_LEVEL;
    requireFeed(feed);
    requireKey(originalKey, "originalKey");
    requireKey(variantKey, "variantKey");
    requireDisplayOrder(displayOrder);
    requireBlurLevel(resolvedBlurLevel);
    return FeedImage.builder()
        .feed(feed)
        .displayOrder(displayOrder)
        .originalKey(originalKey)
        .variantKey(variantKey)
        .blurLevel(resolvedBlurLevel)
        .processingStatus(FeedImageProcessingStatus.PROCESSING)
        .build();
  }

  private static void requireFeed(Feed feed) {
    if (feed == null) {
      throw new IllegalArgumentException("피드 이미지는 소속 피드가 필요합니다.");
    }
  }

  private static void requireKey(String key, String fieldName) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException(fieldName + "는 비어 있을 수 없습니다.");
    }
  }

  private static void requireDisplayOrder(Integer displayOrder) {
    if (displayOrder == null
        || displayOrder < MIN_DISPLAY_ORDER
        || displayOrder > MAX_DISPLAY_ORDER) {
      throw new IllegalArgumentException(
          "노출 순서는 %d~%d 범위여야 합니다: %s"
              .formatted(MIN_DISPLAY_ORDER, MAX_DISPLAY_ORDER, displayOrder));
    }
  }

  private static void requireBlurLevel(int blurLevel) {
    if (blurLevel < MIN_BLUR_LEVEL || blurLevel > MAX_BLUR_LEVEL) {
      throw new IllegalArgumentException(
          "블러 강도는 %d~%d 범위여야 합니다: %d"
              .formatted(MIN_BLUR_LEVEL, MAX_BLUR_LEVEL, blurLevel));
    }
  }

  /** 블러본 존재가 확인되어 공개 노출 가능 상태로 전이한다. (HeadObject 200) */
  public void markReady() {
    this.processingStatus = FeedImageProcessingStatus.READY;
  }

  /** 생성 제한 시간(created_at + 5분) 내 블러본이 확인되지 않아 실패 상태로 전이한다. */
  public void markFailed() {
    this.processingStatus = FeedImageProcessingStatus.FAILED;
  }
}
