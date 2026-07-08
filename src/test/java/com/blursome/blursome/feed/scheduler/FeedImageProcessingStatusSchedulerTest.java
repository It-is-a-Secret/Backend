package com.blursome.blursome.feed.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.global.storage.S3StorageService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link FeedImageProcessingStatusScheduler}의 상태 전이 규칙을 S3 존재 확인을 모킹해 검증한다.
 *
 * <ul>
 *   <li>HeadObject 성공 → {@code READY}</li>
 *   <li>객체 없음 && 생성 5분 초과 → {@code FAILED}</li>
 *   <li>객체 없음 && 5분 이내 → {@code PROCESSING} 유지</li>
 *   <li>404 외 S3 오류 → 상태 유지(다음 주기 재확인) 및 나머지 이미지 처리 지속</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FeedImageProcessingStatusSchedulerTest {

  @Mock
  private FeedImageRepository feedImageRepository;

  @Mock
  private S3StorageService storageService;

  @InjectMocks
  private FeedImageProcessingStatusScheduler scheduler;

  @Test
  @DisplayName("HeadObject가 성공하면 READY로 전이한다")
  void transitionsToReady_whenVariantExists() {
    FeedImage image = feedImage("variants/1.webp", LocalDateTime.now().minusMinutes(1));
    givenTargets(image);
    given(storageService.existsVariant("variants/1.webp")).willReturn(true);

    scheduler.pollProcessingStatus();

    assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
  }

  @Test
  @DisplayName("블러본이 없고 생성된 지 5분이 지났으면 FAILED로 전이한다")
  void transitionsToFailed_whenMissingAndExpired() {
    FeedImage image = feedImage("variants/2.webp", LocalDateTime.now().minusMinutes(6));
    givenTargets(image);
    given(storageService.existsVariant("variants/2.webp")).willReturn(false);

    scheduler.pollProcessingStatus();

    assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.FAILED);
  }

  @Test
  @DisplayName("블러본이 없지만 5분 이내면 PROCESSING을 유지한다")
  void staysProcessing_whenMissingAndWithinWindow() {
    FeedImage image = feedImage("variants/3.webp", LocalDateTime.now().minusMinutes(2));
    givenTargets(image);
    given(storageService.existsVariant("variants/3.webp")).willReturn(false);

    scheduler.pollProcessingStatus();

    assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.PROCESSING);
  }

  @Test
  @DisplayName("한 이미지의 S3 확인이 실패해도 상태를 바꾸지 않고 나머지 이미지는 계속 처리한다")
  void skipsOnError_andContinuesOthers() {
    FeedImage failing = feedImage("variants/err.webp", LocalDateTime.now().minusMinutes(6));
    FeedImage healthy = feedImage("variants/ok.webp", LocalDateTime.now().minusMinutes(1));
    givenTargets(failing, healthy);
    given(storageService.existsVariant("variants/err.webp"))
        .willThrow((S3Exception) S3Exception.builder().statusCode(403).build());
    given(storageService.existsVariant("variants/ok.webp")).willReturn(true);

    scheduler.pollProcessingStatus();

    // 5분이 지났지만 404가 아닌 오류라 FAILED로 단정하지 않고 PROCESSING을 유지한다.
    assertThat(failing.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.PROCESSING);
    assertThat(healthy.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
  }

  private void givenTargets(FeedImage... images) {
    given(feedImageRepository
        .findTop100ByProcessingStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            eq(FeedImageProcessingStatus.PROCESSING), any(LocalDateTime.class)))
        .willReturn(List.of(images));
  }

  private FeedImage feedImage(String variantKey, LocalDateTime createdAt) {
    FeedImage image = FeedImage.create(
        Mockito.mock(Feed.class), 1, "originals/x.jpg", variantKey, FeedImage.DEFAULT_BLUR_LEVEL);
    ReflectionTestUtils.setField(image, "createdAt", createdAt);
    return image;
  }
}
