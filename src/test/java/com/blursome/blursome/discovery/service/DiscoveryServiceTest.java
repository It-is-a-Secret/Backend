package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardDetailResponse;
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
import com.blursome.blursome.member.domain.RegistrationStatus;
import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
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
  @Mock
  private BlockRepository blockRepository;

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

  // ===== 카드 상세(#95) =====

  private Feed onboardedFeed(long feedId, long memberId, Gender gender) {
    Feed feed = feed(feedId, memberId, gender, 2000);
    ReflectionTestUtils.setField(feed.getMember(), "registrationStatus",
        RegistrationStatus.COMPLETED);
    return feed;
  }

  private MemberKeyword keyword(Member member, long tagId) {
    KeywordCategory category = KeywordCategory.of("HOBBY", "취미", 1, true);
    KeywordTag tag = KeywordTag.of(category, "T" + tagId, "태그" + tagId);
    ReflectionTestUtils.setField(tag, "id", tagId);
    return MemberKeyword.of(member, tag);
  }

  private void givenDetailTarget(Feed viewerFeed, Feed target) {
    given(feedService.findFeedByMemberId(viewerFeed.getMember().getId()))
        .willReturn(Optional.of(viewerFeed));
    given(feedService.findFeedById(target.getId())).willReturn(Optional.of(target));
  }

  @Test
  @DisplayName("카드 상세는 기본 정보와 관심사를 반환하고 나와 겹치는 키워드에 matched를 표시한다")
  void getCardDetail_returnsKeywordsWithMatched() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    givenDetailTarget(viewerFeed, target);
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(true);
    given(memberKeywordRepository.findByMemberIdWithTag(1L))
        .willReturn(List.of(keyword(viewerFeed.getMember(), 100L)));
    given(memberKeywordRepository.findByMemberIdWithTag(20L))
        .willReturn(List.of(keyword(target.getMember(), 100L), keyword(target.getMember(), 200L)));

    DiscoveryCardDetailResponse result = discoveryService.getCardDetail(1L, 20L);

    assertThat(result.feedId()).isEqualTo(20L);
    assertThat(result.nickName()).isEqualTo("nick-20");
    assertThat(result.keywords())
        .extracting(DiscoveryCardDetailResponse.Keyword::tagId,
            DiscoveryCardDetailResponse.Keyword::matched)
        .containsExactly(tuple(100L, true), tuple(200L, false));
  }

  @Test
  @DisplayName("동성 피드 상세는 존재를 드러내지 않고 404")
  void getCardDetail_sameGender_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.MALE);
    givenDetailTarget(viewerFeed, target);

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 20L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("차단 관계(양방향)인 상대의 상세는 404")
  void getCardDetail_blocked_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    givenDetailTarget(viewerFeed, target);
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(true);

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 20L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("사진 5장이 모두 공개 준비되지 않은 피드의 상세는 404")
  void getCardDetail_notPublishable_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    givenDetailTarget(viewerFeed, target);
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(false);

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 20L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("정지·탈퇴 등 비활성 상대의 상세는 404")
  void getCardDetail_inactiveTarget_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    target.getMember().suspend();
    givenDetailTarget(viewerFeed, target);

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 20L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("존재하지 않는 피드 상세는 404, 조회자가 온보딩 전이면 403")
  void getCardDetail_missingFeedOrViewerNotOnboarded() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(feedService.findFeedById(999L)).willReturn(Optional.empty());
    given(feedService.findFeedByMemberId(2L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 999L))
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
    assertThatThrownBy(() -> discoveryService.getCardDetail(2L, 20L))
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED.getCode());
  }
}
