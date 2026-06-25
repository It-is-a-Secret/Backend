package com.blursome.blursome.discovery.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link DiscoveryRepository}의 후보 필터·커서·정렬을 MySQL(Testcontainers)로 검증한다.
 * 이성 필터, 본인 제외, 탈퇴 제외, feedId 커서·최신순(내림차순) 정렬을 다룬다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class DiscoveryRepositoryTest {

  private static final Long NO_VIEWER = -1L;

  @Autowired
  private DiscoveryRepository discoveryRepository;

  @Autowired
  private TestEntityManager em;

  /** 온보딩 완료(COMPLETED)·활성 회원 + 피드를 저장한다. */
  private Feed persistOnboardedFeed(String suffix, Gender gender) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    member.verifySchoolEmail("school-" + suffix + "@univ.ac.kr");
    member.completeOnboarding("nick-" + suffix);
    em.persist(member);
    Feed feed = Feed.createOnOnboarding(
        member, gender, 2000, Department.COMPUTER_ENGINEERING, Mbti.INTJ);
    em.persist(feed);
    return feed;
  }

  @Test
  @DisplayName("이성(반대 성별) 후보만 조회한다")
  void findCandidates_returnsOppositeGenderOnly() {
    persistOnboardedFeed("f1", Gender.FEMALE);
    persistOnboardedFeed("f2", Gender.FEMALE);
    persistOnboardedFeed("m1", Gender.MALE);
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getGender).containsOnly(Gender.FEMALE);
    assertThat(result).hasSize(2);
  }

  @Test
  @DisplayName("본인(viewerId)은 후보에서 제외한다")
  void findCandidates_excludesSelf() {
    Feed self = persistOnboardedFeed("self", Gender.FEMALE);
    Feed other = persistOnboardedFeed("other", Gender.FEMALE);
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, self.getMember().getId(), PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(other.getId());
  }

  @Test
  @DisplayName("탈퇴(WITHDRAWN) 회원은 후보에서 제외한다")
  void findCandidates_excludesWithdrawn() {
    Feed active = persistOnboardedFeed("active", Gender.FEMALE);
    Feed gone = persistOnboardedFeed("gone", Gender.FEMALE);
    gone.getMember().withdraw();
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(active.getId());
  }

  @Test
  @DisplayName("차단/피차단 회원은 양방향으로 후보에서 제외한다")
  void findCandidates_excludesBlockedBothDirections() {
    Feed viewer = persistOnboardedFeed("viewer", Gender.MALE);
    Feed blockedByViewer = persistOnboardedFeed("bbv", Gender.FEMALE); // viewer가 차단
    Feed blockingViewer = persistOnboardedFeed("biv", Gender.FEMALE);  // viewer를 차단
    Feed visible = persistOnboardedFeed("vis", Gender.FEMALE);
    em.persist(Block.of(viewer.getMember(), blockedByViewer.getMember()));
    em.persist(Block.of(blockingViewer.getMember(), viewer.getMember()));
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, viewer.getMember().getId(), PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(visible.getId());
  }

  @Test
  @DisplayName("Pageable 상한만큼만 후보를 가져온다(정렬·페이지네이션은 앱 레이어)")
  void findCandidates_respectsPageableLimit() {
    persistOnboardedFeed("c1", Gender.FEMALE);
    persistOnboardedFeed("c2", Gender.FEMALE);
    persistOnboardedFeed("c3", Gender.FEMALE);
    em.flush();
    em.clear();

    List<Feed> bounded = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(2));

    assertThat(bounded).hasSize(2);
  }
}
