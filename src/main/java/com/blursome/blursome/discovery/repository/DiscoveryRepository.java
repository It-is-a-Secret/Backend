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
   * 양방향 제외(block, #41) · <b>피드 공개 가능</b>(#72). 점수 계산에 쓰는 {@code member}(활동성 등)는
   * fetch join으로 함께 적재해 N+1을 막는다. {@code pageable}은 후보 수 안전 상한(과부하 방지)으로만 쓴다.
   *
   * <p><b>공개 게이트(#72)</b>: 사진이 정확히 5장이고 전부 {@code READY}인 피드만 후보가 된다
   * ({@link com.blursome.blursome.feed.domain.FeedImage#isPublishable}와 동일 규칙). 온보딩이
   * {@code COMPLETED}여도 이미지 파이프라인을 통과하지 못한 피드(0장·{@code PROCESSING}/{@code FAILED}
   * 혼입)는 깨진 블러본 노출을 막기 위해 제외한다. {@code READY} 개수 = 5로 "정확히 5장"을, 비-READY가
   * 하나도 없음을 {@code not exists}로 강제해 둘을 합쳐 "정확히 5장 전부 READY"를 보장한다.
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
      + "or (b.blocker.id = m.id and b.blocked.id = :viewerId)) "
      + "and 5 = (select count(fi) from FeedImage fi where fi.feed = f "
      + "and fi.processingStatus = com.blursome.blursome.feed.domain.FeedImageProcessingStatus.READY) "
      + "and not exists (select 1 from FeedImage fx where fx.feed = f "
      + "and fx.processingStatus <> com.blursome.blursome.feed.domain.FeedImageProcessingStatus.READY)")
  List<Feed> findCandidates(@Param("gender") Gender gender,
      @Param("viewerId") Long viewerId,
      Pageable pageable);
}
