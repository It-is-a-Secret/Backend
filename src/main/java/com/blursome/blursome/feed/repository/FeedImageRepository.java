package com.blursome.blursome.feed.repository;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedImageRepository extends JpaRepository<FeedImage, Long> {

  List<FeedImage> findByFeedIdOrderByDisplayOrderAsc(Long feedId);

  void deleteByFeedId(Long feedId);

  /**
   * 블러본 생성 상태 폴링용 조회. 주어진 상태이면서 {@code createdAt <= threshold}인 행을
   * {@code createdAt} 오름차순으로 최대 100개 반환한다. 스케줄러(#51)가 {@code threshold = now - 30초}로
   * 호출한다.
   */
  List<FeedImage> findTop100ByProcessingStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
      FeedImageProcessingStatus processingStatus, LocalDateTime threshold);
}
