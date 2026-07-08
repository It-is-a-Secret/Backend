package com.blursome.blursome.discovery.service;

import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.discovery.repository.DiscoveryRepository;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.repository.MemberKeywordRepository;
import com.blursome.blursome.member.domain.Gender;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색(Discovery) 서비스. viewer 기준 이성·온보딩 완료·활성 회원을 가중 점수(K/M/B/D)로 정렬해 페이지로 반환한다(Phase B).
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

  /**
   * viewer에게 보여줄 탐색 카드를 점수순으로 조회한다.
   *
   * <p>viewer 성별의 반대 성별 후보를 필터링한 뒤 K/M/B/D 가중 점수로 정렬해 {@code page}/{@code size}로
   * 잘라 반환한다. {@code size}는 {@code [1,50]}, {@code page}는 0 이상으로 보정한다.
   *
   * @throws BaseException viewer가 온보딩을 완료하지 않아 피드가 없는 경우
   */
  public List<DiscoveryCardResponse> getDiscovery(Long viewerId, int page, int size) {
    Feed viewerFeed = feedService.findFeedByMemberId(viewerId)
        .orElseThrow(() -> BaseException.from(DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED));

    int pageSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    Map<Long, Long> tagToCategory = new HashMap<>();
    DiscoveryProfile viewerProfile = buildViewerProfile(viewerId, viewerFeed, tagToCategory);

    List<Feed> candidates = discoveryRepository.findCandidates(
        viewerFeed.getGender().opposite(), viewerId, PageRequest.ofSize(MAX_CANDIDATES));
    if (candidates.isEmpty()) {
      return List.of();
    }
    Map<Long, Set<Long>> tagsByMember = loadCandidateTags(candidates, tagToCategory);

    LocalDateTime recentThreshold = LocalDateTime.now().minusDays(RECENT_ACTIVE_DAYS);
    List<Scored> scored = new ArrayList<>(candidates.size());
    for (Feed feed : candidates) {
      Set<Long> candTags = tagsByMember.getOrDefault(feed.getMember().getId(), Set.of());
      DiscoveryProfile candProfile = new DiscoveryProfile(
          feed.getMbti(), feed.getBirthYear(), feed.getDepartment(), candTags);
      double score = discoveryScorer.score(
          viewerProfile, candProfile, tagToCategory, keywordRelationCache);
      LocalDateTime lastActive = feed.getMember().getLastActiveAt();
      boolean recent = lastActive != null && lastActive.isAfter(recentThreshold);
      scored.add(new Scored(feed, score, lastActive, recent));
    }

    return scored.stream()
        .sorted(SCORE_ORDER)
        .skip((long) safePage * pageSize)
        .limit(pageSize)
        .map(s -> DiscoveryCardResponse.from(s.feed()))
        .toList();
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

  /** 점수 내림차순 → 최근 7일 접속 우선 → last_active_at 내림차순 → feedId 내림차순. */
  private static final Comparator<Scored> SCORE_ORDER =
      Comparator.comparingDouble(Scored::score).reversed()
          .thenComparing(Scored::recentActive, Comparator.reverseOrder())
          .thenComparing(Scored::lastActiveAt,
              Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(s -> s.feed().getId(), Comparator.reverseOrder());

  private record Scored(Feed feed, double score, LocalDateTime lastActiveAt, boolean recentActive) {

  }
}
