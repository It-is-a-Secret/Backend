package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.mockito.ArgumentCaptor;
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

  @InjectMocks
  private DiscoveryService discoveryService;

  private Feed feed(Long id, Gender gender, String nick, int birthYear,
      Department department, Mbti mbti) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + nick, "name", "e@e.com", null);
    ReflectionTestUtils.setField(member, "nickName", nick);
    Feed feed = Feed.createOnOnboarding(member, gender, birthYear, department, mbti);
    if (id != null) {
      ReflectionTestUtils.setField(feed, "id", id);
    }
    return feed;
  }

  @Test
  @DisplayName("viewer가 남성이면 여성 후보를 조회하고 카드로 매핑한다")
  void getDiscovery_maleViewer_queriesFemaleCandidates_andMaps() {
    // given
    Feed viewerFeed = feed(1L, Gender.MALE, "me", 2000, Department.COMPUTER_ENGINEERING, Mbti.INTJ);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    Feed candidate = feed(10L, Gender.FEMALE, "her", 2001, Department.SOFTWARE, Mbti.ENFP);
    given(discoveryRepository.findCandidates(
        eq(Gender.FEMALE), eq(1L), isNull(), any(Pageable.class)))
        .willReturn(List.of(candidate));

    // when
    List<DiscoveryCardResponse> result = discoveryService.getDiscovery(1L, null, 20);

    // then
    assertThat(result).hasSize(1);
    DiscoveryCardResponse card = result.get(0);
    assertThat(card.feedId()).isEqualTo(10L);
    assertThat(card.gender()).isEqualTo(Gender.FEMALE);
    assertThat(card.department()).isEqualTo(Department.SOFTWARE);
    assertThat(card.departmentLabel()).isEqualTo("소프트웨어학과");
  }

  @Test
  @DisplayName("size는 [1,50]으로 보정된다(과도하게 큰 값 → 50)")
  void getDiscovery_clampsPageSize() {
    // given
    Feed viewerFeed = feed(1L, Gender.FEMALE, "me", 2000, Department.SOFTWARE, Mbti.INTJ);
    given(feedService.findFeedByMemberId(1L)).willReturn(Optional.of(viewerFeed));
    given(discoveryRepository.findCandidates(any(), any(), any(), any(Pageable.class)))
        .willReturn(List.of());

    // when
    discoveryService.getDiscovery(1L, null, 999);

    // then
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    org.mockito.Mockito.verify(discoveryRepository)
        .findCandidates(eq(Gender.MALE), eq(1L), isNull(), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("viewer가 온보딩(피드)을 완료하지 않았으면 DISCOVERY_ONBOARDING_REQUIRED 예외")
  void getDiscovery_whenViewerHasNoFeed_thenThrows() {
    // given
    given(feedService.findFeedByMemberId(99L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> discoveryService.getDiscovery(99L, null, 20))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED.getCode());
  }
}
