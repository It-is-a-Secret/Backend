package com.blursome.blursome.feed.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link FeedImageProcessingStatusScheduler}의 상태 전이 규칙을 S3 존재 확인을 모킹해 검증한다.
 *
 * <ul>
 *   <li>HeadObject 성공 → {@code READY}</li>
 *   <li>객체 없음 && 처리 시작 5분 초과 → {@code FAILED}</li>
 *   <li>객체 없음 && 5분 이내 → {@code PROCESSING} 유지</li>
 *   <li>404 외 S3 오류 → 상태 유지(다음 주기 재확인) 및 나머지 이미지 처리 지속</li>
 * </ul>
 *
 * <p>전이는 엔티티 변경이 아니라 확인한 {@code id + variantKey} 세대에 대한 조건부 UPDATE로 일어나야 한다(#105).
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
  @DisplayName("HeadObject가 성공하면 확인한 세대(id+variantKey)를 READY로 조건부 전이한다")
  void pollProcessingStatus_whenVariantExists_transitionsToReady() {
    // given
    FeedImage image = feedImage(1L, "variants/1.jpg", LocalDateTime.now().minusMinutes(1));
    givenTargets(image);
    given(storageService.existsVariant("variants/1.jpg")).willReturn(true);
    givenTransitionSucceeds();

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository).transitionProcessingStatus(1L, "variants/1.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY);
    // 엔티티를 더티 체킹으로 고치지 않으므로, 다른 트랜잭션의 key·순서 변경을 덮어쓸 수 없다.
    assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.PROCESSING);
  }

  @Test
  @DisplayName("블러본이 없고 처리 시작 후 5분이 지났으면 FAILED로 조건부 전이한다")
  void pollProcessingStatus_whenMissingAndExpired_transitionsToFailed() {
    // given
    FeedImage image = feedImage(2L, "variants/2.jpg", LocalDateTime.now().minusMinutes(6));
    givenTargets(image);
    given(storageService.existsVariant("variants/2.jpg")).willReturn(false);
    givenTransitionSucceeds();

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository).transitionProcessingStatus(2L, "variants/2.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.FAILED);
  }

  @Test
  @DisplayName("블러본이 없지만 5분 이내면 상태를 바꾸지 않는다")
  void pollProcessingStatus_whenMissingWithinWindow_keepsProcessing() {
    // given
    FeedImage image = feedImage(3L, "variants/3.jpg", LocalDateTime.now().minusMinutes(2));
    givenTargets(image);
    given(storageService.existsVariant("variants/3.jpg")).willReturn(false);

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository, never()).transitionProcessingStatus(anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("재처리로 다시 시작한 행은 createdAt이 오래돼도 처리 시작 5분 이내면 상태를 바꾸지 않는다")
  void pollProcessingStatus_whenReprocessedRecently_keepsProcessingDespiteOldCreatedAt() {
    // given
    FeedImage image = feedImage(4L, "variants/4.jpg", LocalDateTime.now().minusMinutes(1));
    ReflectionTestUtils.setField(image, "createdAt", LocalDateTime.now().minusDays(3));
    givenTargets(image);
    given(storageService.existsVariant("variants/4.jpg")).willReturn(false);

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository, never()).transitionProcessingStatus(anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("처리 시작 시각이 없는 이전 행은 createdAt 기준으로 5분 타임아웃을 판정한다")
  void pollProcessingStatus_whenLegacyRowExpiredByCreatedAt_transitionsToFailed() {
    // given
    FeedImage image = feedImage(5L, "variants/5.jpg", null);
    ReflectionTestUtils.setField(image, "createdAt", LocalDateTime.now().minusMinutes(6));
    givenTargets(image);
    given(storageService.existsVariant("variants/5.jpg")).willReturn(false);
    givenTransitionSucceeds();

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository).transitionProcessingStatus(5L, "variants/5.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.FAILED);
  }

  @Test
  @DisplayName("확인 중 사진이 재처리돼 조건부 UPDATE가 0건이어도 예외 없이 다음 이미지를 처리한다")
  void pollProcessingStatus_whenGenerationChanged_skipsAndContinues() {
    // given
    FeedImage reprocessed = feedImage(6L, "variants/old.jpg", LocalDateTime.now().minusMinutes(1));
    FeedImage healthy = feedImage(7L, "variants/ok.jpg", LocalDateTime.now().minusMinutes(1));
    givenTargets(reprocessed, healthy);
    given(storageService.existsVariant(anyString())).willReturn(true);
    given(feedImageRepository.transitionProcessingStatus(6L, "variants/old.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY)).willReturn(0);
    given(feedImageRepository.transitionProcessingStatus(7L, "variants/ok.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY)).willReturn(1);

    // when
    scheduler.pollProcessingStatus();

    // then
    verify(feedImageRepository).transitionProcessingStatus(7L, "variants/ok.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY);
  }

  @Test
  @DisplayName("한 이미지의 S3 확인이 실패해도 상태를 바꾸지 않고 나머지 이미지는 계속 처리한다")
  void pollProcessingStatus_whenS3Error_skipsAndContinuesOthers() {
    // given
    FeedImage failing = feedImage(8L, "variants/err.jpg", LocalDateTime.now().minusMinutes(6));
    FeedImage healthy = feedImage(9L, "variants/ok.jpg", LocalDateTime.now().minusMinutes(1));
    givenTargets(failing, healthy);
    given(storageService.existsVariant("variants/err.jpg"))
        .willThrow((S3Exception) S3Exception.builder().statusCode(403).build());
    given(storageService.existsVariant("variants/ok.jpg")).willReturn(true);
    givenTransitionSucceeds();

    // when
    scheduler.pollProcessingStatus();

    // then: 5분이 지났지만 404가 아닌 오류라 FAILED로 단정하지 않는다.
    verify(feedImageRepository, never()).transitionProcessingStatus(eq(8L), anyString(), any(), any());
    verify(feedImageRepository).transitionProcessingStatus(9L, "variants/ok.jpg",
        FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY);
  }

  private void givenTargets(FeedImage... images) {
    given(feedImageRepository.findPollingTargets(
        eq(FeedImageProcessingStatus.PROCESSING), any(LocalDateTime.class), any(Limit.class)))
        .willReturn(List.of(images));
  }

  private void givenTransitionSucceeds() {
    given(feedImageRepository.transitionProcessingStatus(anyLong(), anyString(), any(), any()))
        .willReturn(1);
  }

  /** 처리 시작 시각이 {@code processingStartedAt}인 PROCESSING 이미지를 만든다. */
  private FeedImage feedImage(Long id, String variantKey, LocalDateTime processingStartedAt) {
    FeedImage image = FeedImage.create(
        Mockito.mock(Feed.class), 1, "originals/x.jpg", variantKey, FeedImage.DEFAULT_BLUR_LEVEL);
    ReflectionTestUtils.setField(image, "id", id);
    ReflectionTestUtils.setField(image, "processingStartedAt", processingStartedAt);
    return image;
  }
}
