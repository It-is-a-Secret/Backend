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
   * viewer 기준 탐색 후보를 조회한다(정렬·페이지네이션은 점수 계산 후 앱 레이어에서 수행 — 설계 §17.4).
   *
   * <p>조건: 이성({@code gender}) · 온보딩 완료({@code registrationStatus=COMPLETED}) ·
   * 활성({@code activityStatus=ACTIVE}, {@code withdrawnAt IS NULL}) · 본인 제외 · 차단/피차단
   * 양방향 제외(block, #41). 점수 계산에 쓰는 {@code member}(활동성 등)는 fetch join으로 함께 적재해
   * N+1을 막는다. {@code pageable}은 후보 수 안전 상한(과부하 방지)으로만 쓴다.
   */
  @Query("select f from Feed f "
      + "join fetch f.member m "
      + "where f.gender = :gender "
      + "and m.id <> :viewerId "
      + "and m.activityStatus = com.blursome.blursome.member.domain.ActivityStatus.ACTIVE "
      + "and m.withdrawnAt is null "
      + "and m.registrationStatus = com.blursome.blursome.member.domain.RegistrationStatus.COMPLETED "
      + "and not exists (select 1 from Block b where "
      + "(b.blocker.id = :viewerId and b.blocked.id = m.id) "
      + "or (b.blocker.id = m.id and b.blocked.id = :viewerId))")
  List<Feed> findCandidates(@Param("gender") Gender gender,
      @Param("viewerId") Long viewerId,
      Pageable pageable);
}
