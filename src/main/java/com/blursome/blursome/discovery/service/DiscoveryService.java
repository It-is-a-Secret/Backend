package com.blursome.blursome.discovery.service;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.chat.service.PartnerRoom;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardDetailResponse;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.discovery.repository.DiscoveryRepository;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.repository.MemberKeywordRepository;
import com.blursome.blursome.keyword.service.MemberKeywordService;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Member;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색(Discovery) 서비스. viewer 기준 이성·온보딩 완료·활성 회원을 가중 점수(K/M/B/D)로 정렬해 페이지로 반환한다(Phase B).
 * 온보딩 전 viewer도 성별 구분 없이 최근 접속순으로 둘러볼 수 있다(#122). 대화 시작은 {@link ChatStartService}가 막는다.
 *
 * <p>설계 §17.4대로 필터 후보를 앱 레이어에서 점수 계산→정렬→페이지네이션(offset)한다. 동점은 "최근 7일 접속
 * 우선 → last_active_at 내림차순 → feedId 내림차순"으로 가른다. 설계는 {@code docs/discovery/DISCOVERY_DOMAIN.md}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscoveryService {

  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 50;
  /** 앱 레이어 점수 계산 대상 후보 안전 상한(과부하 방지). MVP 규모에서 충분하다. */
  private static final int MAX_CANDIDATES = 500;
  private static final int RECENT_ACTIVE_DAYS = 7;

  private final DiscoveryRepository discoveryRepository;
  private final FeedService feedService;
  private final MemberKeywordRepository memberKeywordRepository;
  private final DiscoveryScorer discoveryScorer;
  private final KeywordRelationCache keywordRelationCache;
  private final BlockRepository blockRepository;
  private final ChatRoomService chatRoomService;

  /**
   * viewer에게 보여줄 탐색 카드를 점수순으로 조회한다.
   *
   * <p>viewer 성별의 반대 성별 후보를 필터링한 뒤 K/M/B/D 가중 점수로 정렬해 {@code page}/{@code size}로
   * 잘라 반환한다. {@code size}는 {@code [1,50]}, {@code page}는 0 이상으로 보정한다.
   *
   * <p>기존 대화 관계(#112): 다시 시작할 수 없는 관계({@code CLOSED}·{@code REPORTED})는 후보 쿼리에서 빠지고, 이미
   * 대화 중({@code ACTIVE})인 상대는 목록 뒤쪽으로 보낸 뒤 카드에 {@code chatRoom}을 붙인다. 관계는 viewer 기준 쿼리
   * 1회로 읽는다.
   *
   * <p>온보딩 전 viewer(#122): 피드가 없어 성별·관심사·MBTI·생년·학과를 알 수 없으므로, 성별 구분 없이 후보를 모으고
   * 점수를 매기지 않는다(전원 0점). 정렬은 동점 규칙인 "최근 7일 접속 우선 → last_active_at → feedId"가 그대로 결정한다.
   */
  public List<DiscoveryCardResponse> getDiscovery(Long viewerId, int page, int size) {
    Optional<Feed> viewerFeed = feedService.findFeedByMemberId(viewerId);

    int pageSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    Map<Long, Long> tagToCategory = new HashMap<>();
    // 온보딩 전이면 null이다. 이 경우 점수 계산과 후보 태그 조회를 건너뛴다.
    DiscoveryProfile viewerProfile = viewerFeed
        .map(feed -> buildViewerProfile(viewerId, feed, tagToCategory))
        .orElse(null);
    Gender targetGender = viewerFeed.map(feed -> feed.getGender().opposite()).orElse(null);

    List<Feed> candidates = discoveryRepository.findCandidates(
        targetGender, viewerId, PageRequest.ofSize(MAX_CANDIDATES));
    if (candidates.isEmpty()) {
      return List.of();
    }
    Map<Long, Set<Long>> tagsByMember = viewerProfile == null
        ? Map.of()
        : loadCandidateTags(candidates, tagToCategory);
    // viewer가 참여했던 방을 상대 id 기준으로 한 번에 읽는다(#112). 후보 수가 아니라 viewer의 방 수에 비례하는 쿼리 1회다.
    Map<Long, PartnerRoom> roomsByPartner = chatRoomService.findPartnerRooms(viewerId);

    LocalDateTime recentThreshold = LocalDateTime.now().minusDays(RECENT_ACTIVE_DAYS);
    List<Scored> scored = new ArrayList<>(candidates.size());
    for (Feed feed : candidates) {
      PartnerRoom room = roomsByPartner.get(feed.getMember().getId());
      // 다시 시작할 수 없는 관계는 후보 쿼리가 이미 뺀다. 조회 사이 상태가 바뀐 경우를 대비해 한 번 더 거른다.
      if (room != null && room.excludesFromDiscovery()) {
        continue;
      }
      double score = viewerProfile == null
          ? 0.0
          : score(viewerProfile, feed, tagsByMember, tagToCategory);
      LocalDateTime lastActive = feed.getMember().getLastActiveAt();
      boolean recent = lastActive != null && lastActive.isAfter(recentThreshold);
      scored.add(new Scored(feed, room, score, lastActive, recent));
    }

    return scored.stream()
        .sorted(SCORE_ORDER)
        .skip((long) safePage * pageSize)
        .limit(pageSize)
        .map(s -> DiscoveryCardResponse.from(s.feed(), s.room()))
        .toList();
  }

  /**
   * 탐색 카드 상세를 조회한다(#95). 상대의 공개 프로필과 관심사 키워드를 반환하고, 조회자와 겹치는 키워드에
   * {@code matched}를 표시한다.
   *
   * <p>feedId 직접 입력으로 탐색 후보 필터를 우회하지 못하도록 목록과 같은 노출 조건(이성·활성·온보딩 완료·차단
   * 양방향 아님·피드 공개 5장 READY·다시 시작할 수 없는 관계 아님)을 재검증한다. 조건 미충족은 피드 존재 여부를
   * 드러내지 않도록 없음과 같은 404로 응답한다. 이미 대화 중이면 그 방을 {@code chatRoom}으로 함께 내린다(#112).
   *
   * <p>온보딩 전 조회자(#122)는 목록과 같이 이성 조건 없이 검증하며, 선택한 키워드가 없어 {@code matched}는 모두
   * {@code false}다.
   *
   * @throws BaseException 대상이 없거나 노출 조건을 충족하지 않는 경우(404)
   */
  public DiscoveryCardDetailResponse getCardDetail(Long viewerId, Long feedId) {
    Feed viewerFeed = feedService.findFeedByMemberId(viewerId).orElse(null);
    Feed targetFeed = feedService.findFeedById(feedId)
        .filter(feed -> isVisibleTo(viewerId, viewerFeed, feed))
        .orElseThrow(() -> BaseException.from(DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND));
    // 목록과 같은 기준으로 다시 시작할 수 없는 관계(CLOSED·REPORTED)는 404로 가리고, 이미 대화 중이면 방을 함께 내린다(#112).
    PartnerRoom room = chatRoomService.findPartnerRoom(viewerId, targetFeed.getMember().getId())
        .orElse(null);
    if (room != null && room.excludesFromDiscovery()) {
      throw BaseException.from(DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND);
    }

    // 관심사 키워드는 온보딩에서 저장되므로 온보딩 전 조회자는 겹치는 키워드가 없다.
    Set<Long> viewerTagIds = viewerFeed == null
        ? Set.of()
        : MemberKeywordService.tagIdsOf(memberKeywordRepository.findByMemberIdWithTag(viewerId));
    return DiscoveryCardDetailResponse.of(targetFeed,
        memberKeywordRepository.findByMemberIdWithTag(targetFeed.getMember().getId()), viewerTagIds,
        room);
  }

  /**
   * 탐색 후보 조건({@link DiscoveryRepository#findCandidates})의 단건 판정. 온보딩 전 조회자({@code viewerFeed}가
   * {@code null}, #122)는 성별을 알 수 없어 이성 조건을 빼고 판정한다.
   */
  private boolean isVisibleTo(Long viewerId, Feed viewerFeed, Feed targetFeed) {
    Member target = targetFeed.getMember();
    boolean genderMatches = viewerFeed == null
        || targetFeed.getGender() == viewerFeed.getGender().opposite();
    return genderMatches
        && !target.getId().equals(viewerId)
        && target.canUseService()
        && !blockRepository.existsBlockBetween(viewerId, target.getId())
        && feedService.isFeedPublishable(targetFeed.getId());
  }

  /** 온보딩 완료 viewer 기준으로 후보 한 명의 가중 점수(K/M/B/D)를 계산한다. */
  private double score(DiscoveryProfile viewerProfile, Feed candidate,
      Map<Long, Set<Long>> tagsByMember, Map<Long, Long> tagToCategory) {
    Set<Long> candTags = tagsByMember.getOrDefault(candidate.getMember().getId(), Set.of());
    DiscoveryProfile candProfile = new DiscoveryProfile(
        candidate.getMbti(), candidate.getBirthYear(), candidate.getDepartment(), candTags);
    return discoveryScorer.score(viewerProfile, candProfile, tagToCategory, keywordRelationCache);
  }

  private DiscoveryProfile buildViewerProfile(Long viewerId, Feed viewerFeed,
      Map<Long, Long> tagToCategory) {
    Set<Long> viewerTagIds = new HashSet<>();
    for (MemberKeyword mk : memberKeywordRepository.findByMemberIdWithTag(viewerId)) {
      Long tagId = mk.getTag().getId();
      viewerTagIds.add(tagId);
      tagToCategory.put(tagId, mk.getTag().getCategory().getId());
    }
    return new DiscoveryProfile(
        viewerFeed.getMbti(), viewerFeed.getBirthYear(), viewerFeed.getDepartment(), viewerTagIds);
  }

  /** 후보들의 태그를 한 번에 조회해 member별 집합으로 묶고, {@code tagToCategory}를 채운다(N+1 회피). */
  private Map<Long, Set<Long>> loadCandidateTags(List<Feed> candidates,
      Map<Long, Long> tagToCategory) {
    List<Long> candidateMemberIds = candidates.stream()
        .map(feed -> feed.getMember().getId())
        .toList();
    Map<Long, Set<Long>> tagsByMember = new HashMap<>();
    for (MemberKeyword mk : memberKeywordRepository.findByMemberIdInWithTag(candidateMemberIds)) {
      Long tagId = mk.getTag().getId();
      tagsByMember.computeIfAbsent(mk.getMember().getId(), k -> new HashSet<>()).add(tagId);
      tagToCategory.put(tagId, mk.getTag().getCategory().getId());
    }
    return tagsByMember;
  }

  /**
   * 이미 대화 중이 아닌 상대 우선(#112) → 점수 내림차순 → 최근 7일 접속 우선 → last_active_at 내림차순 → feedId 내림차순.
   * 탐색의 목적은 새 상대 발견이므로, 이미 대화 중인 상대는 점수와 무관하게 뒤로 보내되 카드의 기존 방 진입점은 남긴다.
   */
  private static final Comparator<Scored> SCORE_ORDER =
      Comparator.comparing(Scored::hasActiveRoom)
          .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
          .thenComparing(Scored::recentActive, Comparator.reverseOrder())
          .thenComparing(Scored::lastActiveAt,
              Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(s -> s.feed().getId(), Comparator.reverseOrder());

  /** @param room 이 후보와 이미 있는 채팅방. 없으면 null */
  private record Scored(Feed feed, PartnerRoom room, double score, LocalDateTime lastActiveAt,
      boolean recentActive) {

    boolean hasActiveRoom() {
      return room != null && room.isActive();
    }

  }
}
