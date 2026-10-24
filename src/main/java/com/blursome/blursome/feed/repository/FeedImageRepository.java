package com.blursome.blursome.feed.repository;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * 스케줄러가 확인한 블러본 <b>세대</b>가 그대로일 때만 처리 상태를 바꾼다(조건부 UPDATE).
   *
   * <p>스케줄러가 행을 읽고 {@code HeadObject}를 확인하는 사이에 저장 API가 같은 사진을 재처리하면 {@code variantKey}가
   * 새 key로 바뀐다. 이때 이전 블러본의 확인 결과로 새 세대를 {@code READY}/{@code FAILED}로 만들면 안 되므로,
   * {@code id + variantKey + 기대 상태}가 모두 일치하는 경우에만 갱신한다. 처리 상태 컬럼만 바꾸므로 그사이 저장 API가
   * 바꾼 순서도 덮어쓰지 않는다. UPDATE는 커밋된 최신 행을 기준으로 조건을 평가하고, 진행 중인 저장 트랜잭션이 있으면
   * 그 커밋을 기다린다.
   *
   * <p>스케줄러는 S3 확인을 DB 트랜잭션 밖에서 하므로, 이 갱신은 행 잠금을 짧게 쥐도록 자체 트랜잭션으로 실행한다.
   *
   * @return 갱신된 행 수. 0이면 그사이 세대가 바뀌었거나 상태가 이미 달라 건너뛴 것이다.
   */
  @Transactional
  @Modifying
  @Query("""
      update FeedImage i set i.processingStatus = :next
      where i.id = :id and i.variantKey = :variantKey and i.processingStatus = :expected
      """)
  int transitionProcessingStatus(
      @Param("id") Long id,
      @Param("variantKey") String variantKey,
      @Param("expected") FeedImageProcessingStatus expected,
      @Param("next") FeedImageProcessingStatus next);
}
