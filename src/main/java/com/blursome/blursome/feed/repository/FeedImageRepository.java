package com.blursome.blursome.feed.repository;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedImageRepository extends JpaRepository<FeedImage, Long> {

  List<FeedImage> findByFeedIdOrderByDisplayOrderAsc(Long feedId);

  /**
   * 블러본 생성 상태 폴링용 조회. 주어진 상태이면서 처리 시작 시각이 {@code threshold} 이전인 행을 오래된 순으로
   * 최대 {@code limit}개 반환한다. 스케줄러(#51)가 {@code threshold = now - 30초}로 호출한다.
   *
   * <p>처리 시작 시각은 {@code processingStartedAt}이며(#105 재처리 시 갱신), 컬럼 도입 전 행은 값이 없어
   * {@code createdAt}으로 대체한다({@link FeedImage#getProcessingBaseTime()}과 같은 규칙).
   */
  @Query("""
      select i from FeedImage i
      where i.processingStatus = :status
        and coalesce(i.processingStartedAt, i.createdAt) <= :threshold
      order by coalesce(i.processingStartedAt, i.createdAt) asc, i.id asc
      """)
  List<FeedImage> findPollingTargets(
      @Param("status") FeedImageProcessingStatus status,
      @Param("threshold") LocalDateTime threshold,
      Limit limit);
}
