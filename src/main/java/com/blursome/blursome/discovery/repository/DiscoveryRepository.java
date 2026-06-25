package com.blursome.blursome.discovery.repository;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.member.domain.Gender;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 탐색 후보 조회 전용 리포지토리. {@link Feed}를 대상으로 하지만 탐색 관심사를 피드 도메인과 분리하기 위해
 * 별도 리포지토리로 둔다(설계 {@code docs/discovery/DISCOVERY_DOMAIN.md}).
 */
public interface DiscoveryRepository extends Repository<Feed, Long> {

  /**
   * viewer 기준 탐색 후보를 최신순(feedId 내림차순)으로 조회한다.
   *
   * <p>조건: 이성({@code gender}) · 온보딩 완료({@code registrationStatus=COMPLETED}) ·
   * 활성({@code activityStatus=ACTIVE}, {@code withdrawnAt IS NULL}) · 본인 제외. 차단(block) 제외는
   * 후속(#41)이며 본 쿼리에는 미포함이다. {@code cursor}가 null이면 처음부터, 값이 있으면 그 feedId보다
   * 과거(작은 id)만 가져온다.
   */
  @Query("select f from Feed f "
      + "join f.member m "
      + "where f.gender = :gender "
      + "and m.id <> :viewerId "
      + "and m.activityStatus = com.blursome.blursome.member.domain.ActivityStatus.ACTIVE "
      + "and m.withdrawnAt is null "
      + "and m.registrationStatus = com.blursome.blursome.member.domain.RegistrationStatus.COMPLETED "
      + "and (:cursor is null or f.id < :cursor) "
      + "order by f.id desc")
  List<Feed> findCandidates(@Param("gender") Gender gender,
      @Param("viewerId") Long viewerId,
      @Param("cursor") Long cursor,
      Pageable pageable);
}
