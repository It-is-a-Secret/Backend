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
   * <p>조건: 성별({@code gender}) · 온보딩 완료({@code registrationStatus=COMPLETED}) ·
   * 활성({@code activityStatus=ACTIVE}, {@code withdrawnAt IS NULL}) · 본인 제외 · 차단/피차단
   * 양방향 제외(block, #41) · <b>피드 공개 가능</b>(#72). 점수 계산에 쓰는 {@code member}(활동성 등)는
   * fetch join으로 함께 적재해 N+1을 막는다. {@code pageable}은 후보 수 안전 상한(과부하 방지)으로만 쓴다.
   *
   * <p><b>성별 조건(#122)</b>: 온보딩 완료 viewer는 반대 성별을 넘긴다. 온보딩 전 viewer는 자기 성별이 아직 없으므로
   * {@code null}을 넘기고, 이때는 성별 조건 없이 조회한다.
   *
   * <p><b>공개 게이트(#72)</b>: 사진이 정확히 5장이고 전부 {@code READY}인 피드만 후보가 된다
   * ({@link com.blursome.blursome.feed.domain.FeedImage#isPublishable}와 동일 규칙). 온보딩이
   * {@code COMPLETED}여도 이미지 파이프라인을 통과하지 못한 피드(0장·{@code PROCESSING}/{@code FAILED}
   * 혼입)는 깨진 블러본 노출을 막기 위해 제외한다. {@code READY} 개수 = 5로 "정확히 5장"을, 비-READY가
   * 하나도 없음을 {@code not exists}로 강제해 둘을 합쳐 "정확히 5장 전부 READY"를 보장한다.
   *
   * <p><b>다시 시작할 수 없는 관계 제외(#112)</b>: viewer와의 방이 {@code CLOSED}(관계 영구 종료)나 {@code REPORTED}
   * (신고 검토 대기)인 후보는 대화 시작이 항상 409로 거절되므로 뺀다. 점수 계산·페이지 자르기 전에 DB에서 빼야 후보 상한
   * ({@code pageable})이 실제 노출 가능한 후보로 채워지고 페이지 경계가 흔들리지 않는다. viewer가 나간 방({@code leftAt})도
   * 포함해 판정한다. {@code pair_key}는 문자열 조립이 필요해 인덱스를 쓰기 어려우므로, viewer 멤버십({@code member_id}
   * 인덱스)에서 같은 방의 상대 행으로 조인한다. 이미 대화 중({@code ACTIVE})인 후보는 남기고 정렬에서 뒤로 보낸다
   * ({@code DiscoveryService}).
   */
  @Query("select f from Feed f "
      + "join fetch f.member m "
      + "where (:gender is null or f.gender = :gender) "
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
      + "and fx.processingStatus <> com.blursome.blursome.feed.domain.FeedImageProcessingStatus.READY) "
      + "and not exists (select 1 from ChatRoomMember mine, ChatRoomMember other "
      + "where mine.member.id = :viewerId and other.member = m and other.chatRoom = mine.chatRoom "
      + "and mine.chatRoom.roomStatus in ("
      + "com.blursome.blursome.chat.domain.ChatRoomStatus.CLOSED, "
      + "com.blursome.blursome.chat.domain.ChatRoomStatus.REPORTED))")
  List<Feed> findCandidates(@Param("gender") Gender gender,
      @Param("viewerId") Long viewerId,
      Pageable pageable);
}
