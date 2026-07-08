package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.discovery.repository.DiscoveryRepository;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

  @Mock
  private DiscoveryRepository discoveryRepository;
  @Mock
  private FeedService feedService;
  @Mock
  private com.blursome.blursome.keyword.repository.MemberKeywordRepository memberKeywordRepository;
  @Mock
  private DiscoveryScorer discoveryScorer;
  @Mock
  private KeywordRelationCache keywordRelationCache;

  @InjectMocks
  private DiscoveryService discoveryService;

  private Feed feed(long feedId, long memberId, Gender gender, int birthYear) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + memberId, "name", "e@e.com", null);
    ReflectionTestUtils.setField(member, "id", memberId);
    ReflectionTestUtils.setField(member, "nickName", "nick-" + feedId);
    Feed feed = Feed.createOnOnboarding(
        member, gender, birthYear, Department.COMPUTER_ENGINEERING, Mbti.INTJ);
    ReflectionTestUtils.setField(feed, "id", feedId);
    return feed;
  }

  @Test
  @DisplayName("남성 viewer는 여성 후보를 점수 내림차순으로 정렬해 반환한다")
  void getDiscovery_sortsByScoreDescending() {
    Feed viewerFeed = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    Feed low = feed(10L, 10L, Gender.FEMALE, 2000);   // 낮은 점수
    Feed high = feed(20L, 20L, Gender.FEMALE, 2001);  // 높은 점수
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(low, high));
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2000), any(), any()))
        .willReturn(0.3);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2001), any(), any()))
        .willReturn(0.9);

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 0, 20);

    assertThat(result).extracting(DiscoveryCardResponse::feedId)
        .containsExactly(20L, 10L);
  }

  @Test
  @DisplayName("page/size로 잘라 반환한다(3명, size=2, page=1 → 1명)")
  void getDiscovery_paginatesByPageAndSize() {
    Feed viewerFeed = feed(1L, 1L, Gender.FEMALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    Feed c1 = feed(11L, 11L, Gender.MALE, 1991); // score 0.1
    Feed c2 = feed(12L, 12L, Gender.MALE, 1992); // score 0.2
    Feed c3 = feed(13L, 13L, Gender.MALE, 1993); // score 0.3
    given(discoveryRepository.findCandidates(eq(Gender.MALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(c1, c2, c3));
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1991), any(), any()))
        .willReturn(0.1);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1992), any(), any()))
        .willReturn(0.2);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1993), any(), any()))
        .willReturn(0.3);

    // 정렬: c3(0.3), c2(0.2), c1(0.1) → page=1,size=2 → 세 번째인 c1만
    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 1, 2);

    assertThat(result).extracting(DiscoveryCardResponse::feedId).containsExactly(11L);
  }

  @Test
  @DisplayName("viewer가 온보딩(피드)을 완료하지 않았으면 DISCOVERY_ONBOARDING_REQUIRED 예외")
  void getDiscovery_whenViewerHasNoFeed_thenThrows() {
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> discoveryService.getDiscovery(99L, 0, 20))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED.getCode());
  }
}
