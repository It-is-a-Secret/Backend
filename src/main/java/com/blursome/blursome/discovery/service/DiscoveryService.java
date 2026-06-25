package com.blursome.blursome.discovery.service;

import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.discovery.repository.DiscoveryRepository;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Gender;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색(Discovery) 서비스. viewer 기준으로 이성·온보딩 완료·활성 회원을 최신순으로 추려 반환한다(Phase A).
 *
 * <p>가중 점수(K/M/B/D) 정렬은 Phase B에서 얹는다. 설계는 {@code docs/discovery/DISCOVERY_DOMAIN.md} 참조.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscoveryService {

  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 50;

  private final DiscoveryRepository discoveryRepository;
  private final FeedService feedService;

  /**
   * viewer에게 보여줄 탐색 카드를 최신순으로 조회한다.
   *
   * <p>viewer의 성별은 본인 피드에서 얻어 반대 성별 후보를 거른다. viewer가 온보딩(피드 생성) 전이면 예외.
   * {@code size}는 {@code [1, 50]}으로 보정한다. 다음 페이지는 마지막 카드의 {@code feedId}를 커서로 재요청한다.
   *
   * @throws BaseException viewer가 온보딩을 완료하지 않아 피드가 없는 경우
   */
  public List<DiscoveryCardResponse> getDiscovery(Long viewerId, Long cursor, int size) {
    Feed viewerFeed = feedService.findFeedByMemberId(viewerId)
        .orElseThrow(() -> BaseException.from(DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED));

    Gender targetGender = viewerFeed.getGender().opposite();
    int pageSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);

    return discoveryRepository
        .findCandidates(targetGender, viewerId, cursor, PageRequest.ofSize(pageSize))
        .stream()
        .map(DiscoveryCardResponse::from)
        .toList();
  }
}
