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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.List;
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
 * <p>{@code processingStartedAt}을 제외한 모든 컬럼은 {@code nullable=false}로 두어 행이 존재하면 항상 완전한
 * 정보를 갖도록 한다. {@code variantKey}가 non-null이라고 해서 블러본 객체가 S3에 존재함을 보장하지는 않으며,
 * 실제 존재는 {@link FeedImageProcessingStatus}로 추적한다.
 */
@Entity
@Getter
@Table(
    name = "feed_image",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_feed_image_order",
            columnNames = {"feed_id", "display_order"})
    },
    // 탐색 후보의 공개 게이트(정확히 5장 전부 READY, #72) 서브쿼리가 feed_id로 좁히고
    // processing_status로 집계하므로 복합 인덱스로 뒷받침한다.
    indexes = @Index(name = "idx_feed_image_feed_status", columnList = "feed_id, processing_status")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedImage extends BaseEntity {

  /** 블러 강도 기본값. 프론트가 지정하지 않으면 적용된다. */
  public static final int DEFAULT_BLUR_LEVEL = 80;

  /** 피드 내 노출 순서 하한(1-base). */
  public static final int MIN_DISPLAY_ORDER = 1;
  /** 피드당 사진 최대 장수이자 노출 순서 상한. */
  public static final int MAX_DISPLAY_ORDER = 5;
  /**
   * 피드를 공개(탐색·공개 피드 조회)할 수 있는 필수 사진 장수. 정확히 이 장수가 모두 {@code READY}여야
   * 공개 대상이 된다(#72). {@link #MAX_DISPLAY_ORDER}와 같은 값으로, 피드는 사진을 빠짐없이 채워야 공개된다.
   */
  public static final int REQUIRED_PUBLISH_COUNT = MAX_DISPLAY_ORDER;
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

  /**
   * 현재 블러본 생성이 시작된 시각. 신규 저장과 blur 재처리(#105) 때 갱신되며, 스케줄러의 최초 확인 지연·5분
   * 타임아웃 기준이 된다. 재처리는 기존 행(ID)을 유지하므로 {@code createdAt}을 기준으로 쓰면 재처리 직후
   * 바로 FAILED가 될 수 있어 별도로 둔다.
   *
   * <p>컬럼 도입 전에 저장된 행은 값이 없을 수 있어 nullable이며, 이때는 {@link #getProcessingBaseTime()}이
   * {@code createdAt}으로 대체한다.
   */
  @Column(name = "processing_started_at")
  private LocalDateTime processingStartedAt;

  @Builder(access = AccessLevel.PRIVATE)
  private FeedImage(Feed feed, Integer displayOrder, String originalKey, String variantKey,
      Integer blurLevel, FeedImageProcessingStatus processingStatus,
      LocalDateTime processingStartedAt) {
    this.feed = feed;
    this.displayOrder = displayOrder;
    this.originalKey = originalKey;
    this.variantKey = variantKey;
    this.blurLevel = blurLevel;
    this.processingStatus = processingStatus;
    this.processingStartedAt = processingStartedAt;
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
        .processingStartedAt(LocalDateTime.now())
        .build();
  }

  /**
   * 노출 순서를 바꾼다. 단순 재정렬이므로 key·블러 강도·처리 상태는 그대로 둔다.
   *
   * @throws IllegalArgumentException 순서가 1~5 범위를 벗어난 경우
   */
  public void changeDisplayOrder(Integer displayOrder) {
    requireDisplayOrder(displayOrder);
    this.displayOrder = displayOrder;
  }

  /**
   * 순서 교환(예: 1↔2) 중 {@code uk_feed_image_order} 충돌을 피하려고 순서를 잠시 유효 범위 밖(음수)으로 옮긴다.
   * 같은 피드의 현재 순서는 서로 다르므로 음수로 바꾼 값도 서로 겹치지 않는다. 호출자는 같은 트랜잭션 안에서
   * 반드시 {@link #changeDisplayOrder(Integer)}로 최종 순서를 다시 지정해야 한다.
   */
  public void moveToTemporaryDisplayOrder() {
    this.displayOrder = -Math.abs(this.displayOrder);
  }

  /** 요청한 블러 강도가 현재 값과 달라 블러본을 다시 만들어야 하는지 여부. */
  public boolean needsReprocessing(int requestedBlurLevel) {
    return this.blurLevel != requestedBlurLevel;
  }

  /**
   * 블러 강도 변경으로 블러본 재생성을 시작한다(#105 copy-on-reprocess). 원본은 새 key로 이미 복사된 상태여야
   * 한다. 행의 ID는 유지한 채 key·블러 강도를 새 값으로 바꾸고 {@code PROCESSING}부터 다시 추적한다. 이전 key의
   * 객체는 덮어쓰지 않고 고아로 남겨 cleanup 대상으로 둔다.
   *
   * @throws IllegalArgumentException key가 비어 있거나 블러 강도가 허용 범위를 벗어난 경우
   */
  public void startReprocessing(String newOriginalKey, String newVariantKey, int newBlurLevel) {
    requireKey(newOriginalKey, "originalKey");
    requireKey(newVariantKey, "variantKey");
    requireBlurLevel(newBlurLevel);
    this.originalKey = newOriginalKey;
    this.variantKey = newVariantKey;
    this.blurLevel = newBlurLevel;
    this.processingStatus = FeedImageProcessingStatus.PROCESSING;
    this.processingStartedAt = LocalDateTime.now();
  }

  /**
   * 스케줄러가 처리 경과 시간을 잴 기준 시각. 컬럼 도입 전 행은 {@code processingStartedAt}이 없으므로
   * {@code createdAt}으로 대체한다.
   */
  public LocalDateTime getProcessingBaseTime() {
    return processingStartedAt != null ? processingStartedAt : getCreatedAt();
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

  /**
   * 블러본 존재가 확인되어 공개 노출 가능한 상태({@link FeedImageProcessingStatus#READY})인지 여부.
   * 공개 피드 조회의 전부-READY 게이트(§2-5)에서 사용한다.
   */
  public boolean isReady() {
    return this.processingStatus == FeedImageProcessingStatus.READY;
  }

  /**
   * 피드가 다른 회원에게 공개(탐색·공개 피드 조회)될 수 있는지 판정하는 <b>단일 게이트</b>다(#72).
   * 사진이 <b>정확히 {@link #REQUIRED_PUBLISH_COUNT}장이고 전부 {@code READY}</b>일 때만 {@code true}.
   * 장수 미달/초과·{@code PROCESSING}/{@code FAILED} 혼입은 깨진/불완전 블러본 노출을 막기 위해 비공개로 본다.
   *
   * <p>탐색 후보 질의({@code DiscoveryRepository})는 같은 규칙을 JPQL 서브쿼리로 강제하므로, 양쪽이
   * 의미상 동일해야 한다.
   */
  public static boolean isPublishable(List<FeedImage> images) {
    return images.size() == REQUIRED_PUBLISH_COUNT && images.stream().allMatch(FeedImage::isReady);
  }

  /** 블러본 존재가 확인되어 공개 노출 가능 상태로 전이한다. (HeadObject 200) */
  public void markReady() {
    this.processingStatus = FeedImageProcessingStatus.READY;
  }

  /** 처리 제한 시간(처리 시작 + 5분) 내 블러본이 확인되지 않아 실패 상태로 전이한다. */
  public void markFailed() {
    this.processingStatus = FeedImageProcessingStatus.FAILED;
  }
}
