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
}
