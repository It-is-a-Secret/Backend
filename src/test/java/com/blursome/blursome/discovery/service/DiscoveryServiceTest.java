package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.chat.service.PartnerRoom;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardDetailResponse;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.dto.response.DiscoveryChatRoomResponse;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.service.MemberService;
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
  @Mock
  private ChatRoomService chatRoomService;
  @Mock
  private MemberService memberService;

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
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2000), any(), any(), any(), any()))
        .willReturn(0.3);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2001), any(), any(), any(), any()))
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
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1991), any(), any(), any(), any()))
        .willReturn(0.1);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1992), any(), any(), any(), any()))
        .willReturn(0.2);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1993), any(), any(), any(), any()))
        .willReturn(0.3);

    // 정렬: c3(0.3), c2(0.2), c1(0.1) → page=1,size=2 → 세 번째인 c1만
    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 1, 2);

    assertThat(result).extracting(DiscoveryCardResponse::feedId).containsExactly(11L);
  }

  @Test
  @DisplayName("이미 대화 중인 상대는 점수가 더 높아도 뒤로 가고, 카드에 chatRoom이 붙는다(#112)")
  void getDiscovery_activeRoomGoesLastWithChatRoom() {
    Feed viewerFeed = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    Feed chatting = feed(10L, 10L, Gender.FEMALE, 2001); // 높은 점수지만 이미 대화 중
    Feed fresh = feed(20L, 20L, Gender.FEMALE, 2000);    // 낮은 점수, 새 상대
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(chatting, fresh));
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2001), any(), any(), any(), any()))
        .willReturn(0.9);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 2000), any(), any(), any(), any()))
        .willReturn(0.3);
    given(chatRoomService.findPartnerRooms(1L))
        .willReturn(Map.of(10L, new PartnerRoom(31L, ChatRoomStatus.ACTIVE)));

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 0, 20);

    assertThat(result).extracting(DiscoveryCardResponse::feedId).containsExactly(20L, 10L);
    assertThat(result.get(0).chatRoom()).isNull();
    assertThat(result.get(1).chatRoom())
        .isEqualTo(new DiscoveryChatRoomResponse(31L, ChatRoomStatus.ACTIVE));
  }

  @Test
  @DisplayName("후보 조회 뒤 종료(CLOSED)·검토 중(REPORTED)이 된 관계도 목록에서 한 번 더 거른다(#112)")
  void getDiscovery_filtersClosedOrReportedRelationsDefensively() {
    Feed viewerFeed = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    Feed closed = feed(10L, 10L, Gender.FEMALE, 2000);
    Feed reported = feed(11L, 11L, Gender.FEMALE, 2000);
    Feed visible = feed(12L, 12L, Gender.FEMALE, 2000);
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(closed, reported, visible));
    given(discoveryScorer.score(any(), any(), any(), any(), any(), any())).willReturn(0.5);
    given(chatRoomService.findPartnerRooms(1L)).willReturn(Map.of(
        10L, new PartnerRoom(31L, ChatRoomStatus.CLOSED),
        11L, new PartnerRoom(32L, ChatRoomStatus.REPORTED)));

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 0, 20);

    assertThat(result).extracting(DiscoveryCardResponse::feedId).containsExactly(12L);
  }

  @Test
  @DisplayName("온보딩 완료 viewer는 후보의 마지막 접속 시각을 점수 계산(R)에 넘긴다(#139)")
  void getDiscovery_passesCandidateLastActiveAtToScorer() {
    Feed viewerFeed = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    LocalDateTime lastActiveAt = LocalDateTime.now().minusHours(3);
    Feed candidate = feed(10L, 10L, Gender.FEMALE, 2000);
    ReflectionTestUtils.setField(candidate.getMember(), "lastActiveAt", lastActiveAt);
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(candidate));

    discoveryService.getDiscovery(1L, 0, 20);

    verify(discoveryScorer).score(any(), any(), eq(lastActiveAt),
        argThat(now -> now != null && now.isAfter(lastActiveAt)), any(), any());
  }

  @Test
  @DisplayName("회원 id 1001~1020은 점수가 가장 높아도 대화 중인 상대보다 뒤, 목록 맨 뒤에 온다(#139)")
  void getDiscovery_putsDeprioritizedMembersLast() {
    Feed viewerFeed = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(memberKeywordRepository.findByMemberIdWithTag(1L)).willReturn(List.of());
    given(memberKeywordRepository.findByMemberIdInWithTag(anyList())).willReturn(List.of());

    Feed pinnedFirst = feed(100L, 1001L, Gender.FEMALE, 1990);  // 후순위 고정, 점수 0.99
    Feed pinnedLast = feed(200L, 1020L, Gender.FEMALE, 1991);   // 후순위 고정, 점수 0.95
    Feed chatting = feed(300L, 30L, Gender.FEMALE, 1992);       // 대화 중, 점수 0.5
    Feed normal = feed(400L, 1021L, Gender.FEMALE, 1993);       // 범위 밖, 점수 0.1
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of(pinnedFirst, pinnedLast, chatting, normal));
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1990), any(), any(), any(), any()))
        .willReturn(0.99);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1991), any(), any(), any(), any()))
        .willReturn(0.95);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1992), any(), any(), any(), any()))
        .willReturn(0.5);
    given(discoveryScorer.score(any(), argThat(c -> c != null && c.birthYear() == 1993), any(), any(), any(), any()))
        .willReturn(0.1);
    given(chatRoomService.findPartnerRooms(1L)).willReturn(Map.of(
        30L, new PartnerRoom(31L, ChatRoomStatus.ACTIVE)));

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, 0, 20);

    // 일반(400) → 대화 중(300) → 후순위 고정끼리는 점수순(100 → 200)
    assertThat(result).extracting(DiscoveryCardResponse::feedId)
        .containsExactly(400L, 300L, 100L, 200L);
  }

  @Test
  @DisplayName("온보딩 전 viewer에게도 회원 id 1001~1020은 최근 접속과 무관하게 맨 뒤에 온다(#139)")
  void getDiscovery_whenViewerNotOnboarded_putsDeprioritizedMembersLast() {
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());

    LocalDateTime now = LocalDateTime.now();
    Feed pinned = feed(10L, 1010L, Gender.FEMALE, 2000);  // 방금 접속, 후순위 고정
    Feed stale = feed(20L, 20L, Gender.MALE, 2000);       // 30일 전 접속
    ReflectionTestUtils.setField(pinned.getMember(), "lastActiveAt", now);
    ReflectionTestUtils.setField(stale.getMember(), "lastActiveAt", now.minusDays(30));
    given(discoveryRepository.findCandidates(isNull(), eq(99L), any(Pageable.class)))
        .willReturn(List.of(pinned, stale));

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(99L, 0, 20);

    assertThat(result).extracting(DiscoveryCardResponse::feedId).containsExactly(20L, 10L);
  }

  @Test
  @DisplayName("후순위 고정 범위는 회원 id 1001~1020(양 끝 포함)이다(#139)")
  void isDeprioritized_range() {
    assertThat(DiscoveryService.isDeprioritized(1000L)).isFalse();
    assertThat(DiscoveryService.isDeprioritized(1001L)).isTrue();
    assertThat(DiscoveryService.isDeprioritized(1020L)).isTrue();
    assertThat(DiscoveryService.isDeprioritized(1021L)).isFalse();
    assertThat(DiscoveryService.isDeprioritized(null)).isFalse();
  }

  @Test
  @DisplayName("온보딩 전 viewer(피드 없음)는 성별 구분 없이 후보를 받고, 점수 없이 최근 접속순으로 정렬된다(#122)")
  void getDiscovery_whenViewerNotOnboarded_returnsAllGendersByRecentActivity() {
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());

    LocalDateTime now = LocalDateTime.now();
    Feed old = feed(10L, 10L, Gender.FEMALE, 2000);        // 7일 넘게 미접속
    Feed latest = feed(20L, 20L, Gender.MALE, 2000);       // 1시간 전 접속
    Feed recentLow = feed(30L, 30L, Gender.FEMALE, 2000);  // 2일 전 접속, feedId 작음
    Feed recentHigh = feed(40L, 40L, Gender.MALE, 2000);   // 2일 전 접속(동시각), feedId 큼
    ReflectionTestUtils.setField(old.getMember(), "lastActiveAt", now.minusDays(10));
    ReflectionTestUtils.setField(latest.getMember(), "lastActiveAt", now.minusHours(1));
    ReflectionTestUtils.setField(recentLow.getMember(), "lastActiveAt", now.minusDays(2));
    ReflectionTestUtils.setField(recentHigh.getMember(), "lastActiveAt", now.minusDays(2));
    given(discoveryRepository.findCandidates(isNull(), eq(99L), any(Pageable.class)))
        .willReturn(List.of(old, latest, recentLow, recentHigh));

    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(99L, 0, 20);

    assertThat(result).extracting(DiscoveryCardResponse::feedId)
        .containsExactly(20L, 40L, 30L, 10L);
    verifyNoInteractions(discoveryScorer, memberKeywordRepository);
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
  @DisplayName("카드 상세: 이미 대화 중인 상대면 chatRoom(roomId, ACTIVE)을 함께 내린다(#112)")
  void getCardDetail_withActiveRoom_returnsChatRoom() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    givenDetailTarget(viewerFeed, target);
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(true);
    given(chatRoomService.findPartnerRoom(1L, 20L))
        .willReturn(Optional.of(new PartnerRoom(31L, ChatRoomStatus.ACTIVE)));

    DiscoveryCardDetailResponse result = discoveryService.getCardDetail(1L, 20L);

    assertThat(result.chatRoom()).isEqualTo(new DiscoveryChatRoomResponse(31L, ChatRoomStatus.ACTIVE));
  }

  @Test
  @DisplayName("카드 상세: 방이 없으면 chatRoom은 null이다(#112)")
  void getCardDetail_withoutRoom_chatRoomIsNull() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    givenDetailTarget(viewerFeed, target);
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(true);
    given(chatRoomService.findPartnerRoom(1L, 20L)).willReturn(Optional.empty());

    assertThat(discoveryService.getCardDetail(1L, 20L).chatRoom()).isNull();
  }

  @Test
  @DisplayName("카드 상세: 종료(CLOSED)·검토 중(REPORTED) 관계인 상대는 목록과 같이 404(#112)")
  void getCardDetail_closedOrReportedRelation_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    Feed target = onboardedFeed(20L, 20L, Gender.FEMALE);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(feedService.findFeedById(20L)).willReturn(Optional.of(target));
    given(blockRepository.existsBlockBetween(1L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(true);

    for (ChatRoomStatus status : List.of(ChatRoomStatus.CLOSED, ChatRoomStatus.REPORTED)) {
      given(chatRoomService.findPartnerRoom(1L, 20L))
          .willReturn(Optional.of(new PartnerRoom(31L, status)));

      assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 20L))
          .isInstanceOf(BaseException.class)
          .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
    }
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
  @DisplayName("존재하지 않는 피드 상세는 404")
  void getCardDetail_missingFeed_notFound() {
    Feed viewerFeed = onboardedFeed(1L, 1L, Gender.MALE);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(feedService.findFeedById(999L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> discoveryService.getCardDetail(1L, 999L))
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("온보딩 전 조회자는 성별과 무관하게 카드 상세를 보고, 겹치는 키워드가 없어 matched는 모두 false다(#122)")
  void getCardDetail_viewerNotOnboarded_returnsDetailWithoutMatched() {
    Feed target = onboardedFeed(20L, 20L, Gender.MALE);
    given(feedService.findFeedByMemberId(2L)).willReturn(Optional.empty());
    given(feedService.findFeedById(20L)).willReturn(Optional.of(target));
    given(blockRepository.existsBlockBetween(2L, 20L)).willReturn(false);
    given(feedService.isFeedPublishable(20L)).willReturn(true);
    given(memberKeywordRepository.findByMemberIdWithTag(20L))
        .willReturn(List.of(keyword(target.getMember(), 100L)));

    DiscoveryCardDetailResponse result = discoveryService.getCardDetail(2L, 20L);

    assertThat(result.feedId()).isEqualTo(20L);
    assertThat(result.keywords())
        .extracting(DiscoveryCardDetailResponse.Keyword::tagId,
            DiscoveryCardDetailResponse.Keyword::matched)
        .containsExactly(tuple(100L, false));
  }

  @Test
  @DisplayName("온보딩 전 조회자도 차단 관계·사진 미완성 피드의 상세는 404(#122)")
  void getCardDetail_viewerNotOnboarded_stillAppliesVisibilityRules() {
    Feed blocked = onboardedFeed(20L, 20L, Gender.FEMALE);
    Feed notPublishable = onboardedFeed(30L, 30L, Gender.MALE);
    given(feedService.findFeedByMemberId(2L)).willReturn(Optional.empty());
    given(feedService.findFeedById(20L)).willReturn(Optional.of(blocked));
    given(feedService.findFeedById(30L)).willReturn(Optional.of(notPublishable));
    given(blockRepository.existsBlockBetween(2L, 20L)).willReturn(true);
    given(blockRepository.existsBlockBetween(2L, 30L)).willReturn(false);
    given(feedService.isFeedPublishable(30L)).willReturn(false);

    for (long feedId : List.of(20L, 30L)) {
      assertThatThrownBy(() -> discoveryService.getCardDetail(2L, feedId))
          .hasFieldOrPropertyWithValue("code",
              DiscoveryErrorCode.DISCOVERY_FEED_NOT_FOUND.getCode());
    }
  }

  // ---------- 학교 인증 게이트(#129) ----------

  @Test
  @DisplayName("학교 인증 전(피드 없음) viewer의 탐색 목록은 403이고 후보를 조회하지 않는다(#129)")
  void getDiscovery_whenViewerNotSchoolVerified_forbidden() {
    // given
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());
    willThrow(BaseException.from(MemberErrorCode.MEMBER_SCHOOL_VERIFICATION_REQUIRED_TO_VIEW))
        .given(memberService).requireSchoolVerified(99L);

    // when & then
    assertThatThrownBy(() -> discoveryService.getDiscovery(99L, 0, 20))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_403_SCHOOL_VERIFICATION_REQUIRED");
    verifyNoInteractions(discoveryRepository, chatRoomService);
  }

  @Test
  @DisplayName("학교 인증만 마친(온보딩 전) viewer는 인증 확인 후 탐색 목록을 받는다(#122 유지)")
  void getDiscovery_whenViewerSchoolVerifiedButNotOnboarded_allowed() {
    // given
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());
    given(discoveryRepository.findCandidates(isNull(), eq(99L), any(Pageable.class)))
        .willReturn(List.of());

    // when
    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(99L, 0, 20);

    // then
    assertThat(result).isEmpty();
    verify(memberService).requireSchoolVerified(99L);
  }

  @Test
  @DisplayName("온보딩 완료(피드 보유) viewer는 학교 인증 조회를 하지 않는다 — 추가 쿼리 없음(#129)")
  void getDiscovery_whenViewerOnboarded_skipsVerificationQuery() {
    // given
    Feed viewer = feed(1L, 1L, Gender.MALE, 2000);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewer));
    given(discoveryRepository.findCandidates(eq(Gender.FEMALE), eq(1L), any(Pageable.class)))
        .willReturn(List.of());

    // when
    discoveryService.getDiscovery(1L, 0, 20);

    // then
    verify(memberService, never()).requireSchoolVerified(any());
  }

  @Test
  @DisplayName("학교 인증 전 viewer의 카드 상세는 대상 피드를 읽기 전에 403이다(#129)")
  void getCardDetail_whenViewerNotSchoolVerified_forbiddenBeforeTargetLookup() {
    // given
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());
    willThrow(BaseException.from(MemberErrorCode.MEMBER_SCHOOL_VERIFICATION_REQUIRED_TO_VIEW))
        .given(memberService).requireSchoolVerified(99L);

    // when & then
    assertThatThrownBy(() -> discoveryService.getCardDetail(99L, 20L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_403_SCHOOL_VERIFICATION_REQUIRED");
    verify(feedService, never()).findFeedById(any());
  }
}
