package com.blursome.blursome.feed.scheduler;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.global.storage.S3StorageService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블러본 처리 상태 폴링 스케줄러. Lambda가 비동기로 생성하는 블러본의 존재를 variants 버킷 {@code HeadObject}로
 * 폴링해 {@link FeedImage#getProcessingStatus()}를 전이시킨다.
 *
 * <p>v1은 SQS/DLQ 없이 시간 기반으로 {@code FAILED}를 판정한다. 1분 주기로 {@code PROCESSING} 상태이면서
 * 생성된 지 {@value #INITIAL_DELAY_SECONDS}초가 지난 이미지를 오래된 순으로 최대 100개 확인한다.
 *
 * <ul>
 *   <li>{@code HeadObject} 성공 → {@link FeedImage#markReady()}</li>
 *   <li>객체 없음(404) && {@code created_at <= now - 5분} → {@link FeedImage#markFailed()}</li>
 *   <li>객체 없음(404) && 5분 이내 → {@code PROCESSING} 유지, 다음 주기 재확인</li>
 * </ul>
 *
 * <p>단일 서버 기준 단순 {@code @Scheduled}로 동작한다. 다중 인스턴스 중복 실행 방지(분산 락)는 v1 범위가
 * 아니다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-1)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedImageProcessingStatusScheduler {

  /** 생성 직후 Lambda 미완료 구간 폴링 낭비를 막기 위한 최초 확인 지연. */
  private static final long INITIAL_DELAY_SECONDS = 30;
  /** 이 시간이 지나도 블러본이 확인되지 않으면 {@code FAILED}로 판정한다. */
  private static final Duration FAILURE_TIMEOUT = Duration.ofMinutes(5);
  /** 실행 주기(밀리초). 1분마다 폴링한다. */
  private static final long POLL_INTERVAL_MS = 60_000;

  private final FeedImageRepository feedImageRepository;
  private final S3StorageService storageService;

  /**
   * {@code PROCESSING} 대상의 블러본 존재를 폴링해 상태를 전이시킨다. 변경된 엔티티는 트랜잭션 종료 시 더티
   * 체킹으로 반영된다. 한 이미지의 S3 확인이 실패(404 외 오류)하더라도 나머지 이미지 처리는 계속한다.
   */
  @Scheduled(fixedRate = POLL_INTERVAL_MS)
  @Transactional
  public void pollProcessingStatus() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime checkThreshold = now.minusSeconds(INITIAL_DELAY_SECONDS);
    LocalDateTime failureThreshold = now.minus(FAILURE_TIMEOUT);

    List<FeedImage> targets =
        feedImageRepository.findTop100ByProcessingStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            FeedImageProcessingStatus.PROCESSING, checkThreshold);

    for (FeedImage image : targets) {
      transition(image, failureThreshold);
    }
  }

  private void transition(FeedImage image, LocalDateTime failureThreshold) {
    try {
      if (storageService.existsVariant(image.getVariantKey())) {
        image.markReady();
      } else if (!image.getCreatedAt().isAfter(failureThreshold)) {
        // 객체 없음 && created_at <= now - 5분 → 더 기다려도 생성될 가능성이 낮아 FAILED로 판정한다.
        image.markFailed();
      }
      // 객체 없음 && 5분 이내는 PROCESSING을 유지해 다음 주기에 재확인한다.
    } catch (RuntimeException e) {
      // 404가 아닌 S3 오류(권한·일시 장애 등)는 존재 여부를 단정할 수 없으므로 상태를 바꾸지 않고 다음 주기로 넘긴다.
      // 전제: variants 버킷에 대한 s3:ListBucket 권한이 있어야 부재 객체가 404로 돌아와 FAILED 판정이 동작한다.
      // 권한이 없으면 부재 객체가 403으로 가려져 여기서 무한히 PROCESSING에 고착되므로, 반복되면 IAM을 점검한다.
      // (운영 IAM 정책: FEED_IMAGE_BLUR_PIPELINE.md §8-5)
      log.warn("블러본 존재 확인 실패. feedImageId={}, variantKey={}",
          image.getId(), image.getVariantKey(), e);
    }
  }
}
