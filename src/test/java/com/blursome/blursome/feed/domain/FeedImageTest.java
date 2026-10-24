package com.blursome.blursome.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedImageTest {

  private final Feed feed = mock(Feed.class);

  private FeedImage create(Integer displayOrder, Integer blurLevel) {
    return FeedImage.create(feed, displayOrder, "originals/uuid.jpg", "variants/uuid.jpg", blurLevel);
  }

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("생성 직후 상태는 PROCESSING이고 전달한 값이 그대로 설정된다")
    void create_initializesProcessingStatusAndFields() {
      FeedImage feedImage = create(1, 90);

      assertThat(feedImage.getFeed()).isSameAs(feed);
      assertThat(feedImage.getDisplayOrder()).isEqualTo(1);
      assertThat(feedImage.getOriginalKey()).isEqualTo("originals/uuid.jpg");
      assertThat(feedImage.getVariantKey()).isEqualTo("variants/uuid.jpg");
      assertThat(feedImage.getBlurLevel()).isEqualTo(90);
      assertThat(feedImage.getProcessingStatus())
          .isEqualTo(FeedImageProcessingStatus.PROCESSING);
    }

    @Test
    @DisplayName("blurLevel이 null이면 기본값 80을 적용한다")
    void create_appliesDefaultBlurLevelWhenNull() {
      FeedImage feedImage = create(1, null);

      assertThat(feedImage.getBlurLevel()).isEqualTo(FeedImage.DEFAULT_BLUR_LEVEL);
      assertThat(feedImage.getBlurLevel()).isEqualTo(80);
    }

    @Test
    @DisplayName("경계값(순서 1·5, 블러 50·100)은 허용된다")
    void create_allowsBoundaryValues() {
      assertThat(create(FeedImage.MIN_DISPLAY_ORDER, FeedImage.MIN_BLUR_LEVEL)).isNotNull();
      assertThat(create(FeedImage.MAX_DISPLAY_ORDER, FeedImage.MAX_BLUR_LEVEL)).isNotNull();
    }
  }

  @Nested
  @DisplayName("create 불변식 검증")
  class CreateInvariants {

    @Test
    @DisplayName("feed가 null이면 예외를 던진다")
    void create_rejectsNullFeed() {
      assertThatThrownBy(() ->
          FeedImage.create(null, 1, "originals/uuid.jpg", "variants/uuid.jpg", 80))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("originalKey/variantKey가 비어 있으면 예외를 던진다")
    void create_rejectsBlankKeys() {
      assertThatThrownBy(() ->
          FeedImage.create(feed, 1, " ", "variants/uuid.jpg", 80))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() ->
          FeedImage.create(feed, 1, "originals/uuid.jpg", null, 80))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "displayOrder={0}")
    @ValueSource(ints = {0, 6})
    @DisplayName("displayOrder가 1~5 범위를 벗어나면 예외를 던진다")
    void create_rejectsOutOfRangeDisplayOrder(int displayOrder) {
      assertThatThrownBy(() -> create(displayOrder, 80))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "blurLevel={0}")
    @ValueSource(ints = {49, 101})
    @DisplayName("blurLevel이 50~100 범위를 벗어나면 예외를 던진다")
    void create_rejectsOutOfRangeBlurLevel(int blurLevel) {
      assertThatThrownBy(() -> create(1, blurLevel))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("상태 전이")
  class StatusTransition {

    @Test
    @DisplayName("markReady는 상태를 READY로 전이한다")
    void markReady_transitionsToReady() {
      FeedImage feedImage = create(1, 80);

      feedImage.markReady();

      assertThat(feedImage.getProcessingStatus())
          .isEqualTo(FeedImageProcessingStatus.READY);
    }

    @Test
    @DisplayName("markFailed는 상태를 FAILED로 전이한다")
    void markFailed_transitionsToFailed() {
      FeedImage feedImage = create(1, 80);

      feedImage.markFailed();

      assertThat(feedImage.getProcessingStatus())
          .isEqualTo(FeedImageProcessingStatus.FAILED);
    }
  }

  @Nested
  @DisplayName("isPublishable 공개 게이트(#72)")
  class Publishable {

    /** displayOrder가 1~count인 이미지 목록을 만들고, ready=true면 전부 READY로 전이한다. */
    private java.util.List<FeedImage> images(int count, boolean ready) {
      return java.util.stream.IntStream.rangeClosed(1, count)
          .mapToObj(order -> {
            FeedImage image = create(order, 80);
            if (ready) {
              image.markReady();
            }
            return image;
          })
          .toList();
    }

    @Test
    @DisplayName("정확히 5장 전부 READY면 공개 가능")
    void publishable_whenExactlyFiveReady() {
      assertThat(FeedImage.isPublishable(images(5, true))).isTrue();
    }

    @Test
    @DisplayName("5장이라도 하나가 READY가 아니면 비공개")
    void notPublishable_whenAnyNotReady() {
      java.util.List<FeedImage> images = new java.util.ArrayList<>(images(4, true));
      images.add(create(5, 80)); // PROCESSING
      assertThat(FeedImage.isPublishable(images)).isFalse();
    }

    @Test
    @DisplayName("전부 READY라도 5장 미만이면 비공개")
    void notPublishable_whenFewerThanFive() {
      assertThat(FeedImage.isPublishable(images(4, true))).isFalse();
    }

    @Test
    @DisplayName("0장이면 비공개")
    void notPublishable_whenEmpty() {
      assertThat(FeedImage.isPublishable(java.util.List.of())).isFalse();
    }
  }

  @Nested
  @DisplayName("순서 변경·재처리 (#105)")
  class ReorderAndReprocess {

    @Test
    @DisplayName("생성 시 처리 시작 시각을 기록한다")
    void create_recordsProcessingStartedAt() {
      // given
      java.time.LocalDateTime before = java.time.LocalDateTime.now();

      // when
      FeedImage image = create(1, 80);

      // then
      assertThat(image.getProcessingStartedAt()).isAfterOrEqualTo(before);
      assertThat(image.getProcessingBaseTime()).isEqualTo(image.getProcessingStartedAt());
    }

    @Test
    @DisplayName("순서만 바꾸면 key·블러 강도·상태는 그대로다")
    void changeDisplayOrder_whenValid_keepsOtherFields() {
      // given
      FeedImage image = create(1, 80);
      image.markReady();

      // when
      image.changeDisplayOrder(3);

      // then
      assertThat(image.getDisplayOrder()).isEqualTo(3);
      assertThat(image.getOriginalKey()).isEqualTo("originals/uuid.jpg");
      assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    @DisplayName("범위 밖 순서로는 바꿀 수 없다")
    void changeDisplayOrder_whenOutOfRange_throwsException(int displayOrder) {
      // given
      FeedImage image = create(1, 80);

      // when & then
      assertThatThrownBy(() -> image.changeDisplayOrder(displayOrder))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("임시 순서는 현재 순서의 음수라 같은 피드 안에서 겹치지 않는다")
    void moveToTemporaryDisplayOrder_whenCalled_movesOutOfValidRange() {
      // given
      FeedImage first = create(1, 80);
      FeedImage second = create(2, 80);

      // when
      first.moveToTemporaryDisplayOrder();
      second.moveToTemporaryDisplayOrder();

      // then
      assertThat(first.getDisplayOrder()).isEqualTo(-1);
      assertThat(second.getDisplayOrder()).isEqualTo(-2);
    }

    @Test
    @DisplayName("블러 강도가 현재 값과 다를 때만 재처리가 필요하다")
    void needsReprocessing_whenBlurLevelDiffers_returnsTrue() {
      // given
      FeedImage image = create(1, 80);

      // when & then
      assertThat(image.needsReprocessing(80)).isFalse();
      assertThat(image.needsReprocessing(90)).isTrue();
    }

    @Test
    @DisplayName("재처리를 시작하면 새 key·블러 강도로 바꾸고 PROCESSING과 처리 시작 시각을 갱신한다")
    void startReprocessing_whenCalled_resetsToProcessingWithNewKeys() {
      // given
      FeedImage image = create(2, 80);
      image.markReady();
      java.time.LocalDateTime before = java.time.LocalDateTime.now();

      // when
      image.startReprocessing("originals/new.jpg", "variants/new.jpg", 95);

      // then
      assertThat(image.getDisplayOrder()).isEqualTo(2);
      assertThat(image.getOriginalKey()).isEqualTo("originals/new.jpg");
      assertThat(image.getVariantKey()).isEqualTo("variants/new.jpg");
      assertThat(image.getBlurLevel()).isEqualTo(95);
      assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.PROCESSING);
      assertThat(image.getProcessingStartedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("범위 밖 블러 강도나 빈 key로는 재처리를 시작할 수 없다")
    void startReprocessing_whenInvalid_throwsException() {
      // given
      FeedImage image = create(1, 80);

      // when & then
      assertThatThrownBy(() -> image.startReprocessing("originals/new.jpg", "variants/new.jpg", 49))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> image.startReprocessing(" ", "variants/new.jpg", 90))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(image.getBlurLevel()).isEqualTo(80);
    }

    @Test
    @DisplayName("처리 시작 시각이 없는 이전 행은 createdAt을 기준 시각으로 쓴다")
    void getProcessingBaseTime_whenLegacyRow_fallsBackToCreatedAt() {
      // given
      FeedImage image = create(1, 80);
      java.time.LocalDateTime createdAt = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
      org.springframework.test.util.ReflectionTestUtils.setField(image, "processingStartedAt", null);
      org.springframework.test.util.ReflectionTestUtils.setField(image, "createdAt", createdAt);

      // when & then
      assertThat(image.getProcessingBaseTime()).isEqualTo(createdAt);
    }
  }
}
