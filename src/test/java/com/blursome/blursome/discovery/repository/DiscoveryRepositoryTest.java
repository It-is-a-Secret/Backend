package com.blursome.blursome.discovery.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
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
 * 이성 필터, 본인 제외, 탈퇴 제외, 차단 양방향 제외, 공개 게이트(정확히 5장 전부 READY, #72)를 다룬다.
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

  /**
   * 온보딩 완료(COMPLETED)·활성 회원 + 피드 + <b>공개 가능 사진 5장 전부 READY</b>를 저장한다.
   * 공개 게이트(#72)를 통과하는 기본 후보다.
   */
  private Feed persistOnboardedFeed(String suffix, Gender gender) {
    Feed feed = persistFeedOnly(suffix, gender);
    persistImages(feed, 5, 5); // 5장 전부 READY
    return feed;
  }

  /** 온보딩 완료 회원 + 피드만 저장한다(이미지 없음). 공개 게이트 검증용으로 이미지를 따로 붙인다. */
  private Feed persistFeedOnly(String suffix, Gender gender) {
    return persistFeedOnly(suffix, gender, Mbti.INTJ);
  }

  /** {@code mbti}를 지정해(또는 null="모름", #76) 온보딩 완료 회원 + 피드만 저장한다(이미지 없음). */
  private Feed persistFeedOnly(String suffix, Gender gender, Mbti mbti) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    member.verifySchoolEmail("school-" + suffix + "@univ.ac.kr");
    member.completeOnboarding("nick-" + suffix);
    em.persist(member);
    Feed feed = Feed.createOnOnboarding(
        member, gender, 2000, Department.COMPUTER_ENGINEERING, mbti);
    em.persist(feed);
    return feed;
  }

  /**
   * 피드에 사진을 붙인다. {@code readyCount}장은 READY로, 나머지({@code total - readyCount})장은
   * PROCESSING(기본 상태)으로 둔다. displayOrder는 1..total.
   */
  private void persistImages(Feed feed, int total, int readyCount) {
    for (int order = 1; order <= total; order++) {
      FeedImage image = FeedImage.create(feed, order,
          "originals/" + feed.getId() + "/" + order + ".png",
          "variants/" + feed.getId() + "/" + order + ".jpg", 80);
      if (order <= readyCount) {
        image.markReady();
      }
      em.persist(image);
    }
  }

  /** 두 회원 사이 방을 만들고 두 사람을 참여시킨다. */
  private ChatRoom persistRoom(Feed a, Feed b) {
    ChatRoom room = ChatRoom.createOnMatched(a.getMember().getId(), b.getMember().getId());
    em.persist(room);
    em.persist(ChatRoomMember.join(room, a.getMember()));
    em.persist(ChatRoomMember.join(room, b.getMember()));
    return room;
  }

  @Test
  @DisplayName("viewer와 종료(CLOSED)·검토 중(REPORTED)인 상대는 빼고, 대화 중(ACTIVE)·방 없음 상대는 남긴다(#112)")
  void findCandidates_excludesClosedAndReportedRelations() {
    Feed viewer = persistOnboardedFeed("viewer", Gender.MALE);
    Feed closed = persistOnboardedFeed("closed", Gender.FEMALE);
    Feed reported = persistOnboardedFeed("reported", Gender.FEMALE);
    Feed active = persistOnboardedFeed("active", Gender.FEMALE);
    Feed fresh = persistOnboardedFeed("fresh", Gender.FEMALE);
    persistRoom(viewer, closed).close();
    persistRoom(viewer, reported).markReported();
    persistRoom(viewer, active);
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, viewer.getMember().getId(), PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId)
        .containsExactlyInAnyOrder(active.getId(), fresh.getId());
  }

  @Test
  @DisplayName("viewer가 직접 나가 종료된 방의 상대도 뺀다(나간 멤버십 포함 판정, #112)")
  void findCandidates_excludesClosedRelationEvenIfViewerLeft() {
    Feed viewer = persistOnboardedFeed("viewer", Gender.MALE);
    Feed partner = persistOnboardedFeed("partner", Gender.FEMALE);
    ChatRoom room = ChatRoom.createOnMatched(viewer.getMember().getId(), partner.getMember().getId());
    em.persist(room);
    ChatRoomMember viewerMembership = ChatRoomMember.join(room, viewer.getMember());
    em.persist(viewerMembership);
    em.persist(ChatRoomMember.join(room, partner.getMember()));
    viewerMembership.leave();
    room.close();
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, viewer.getMember().getId(), PageRequest.ofSize(10));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("후보가 다른 회원과 종료된 방이 있어도 viewer와의 관계가 아니면 남긴다(#112)")
  void findCandidates_ignoresClosedRoomsWithOtherMembers() {
    Feed viewer = persistOnboardedFeed("viewer", Gender.MALE);
    Feed someoneElse = persistOnboardedFeed("else", Gender.MALE);
    Feed candidate = persistOnboardedFeed("cand", Gender.FEMALE);
    persistRoom(someoneElse, candidate).close();
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, viewer.getMember().getId(), PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(candidate.getId());
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
  @DisplayName("성별을 null로 넘기면(온보딩 전 viewer, #122) 성별 구분 없이 조회하되 공개 게이트는 그대로 적용한다")
  void findCandidates_withNullGender_returnsAllGenders() {
    Feed female = persistOnboardedFeed("f1", Gender.FEMALE);
    Feed male = persistOnboardedFeed("m1", Gender.MALE);
    persistFeedOnly("m2", Gender.MALE); // 사진 없음 → 공개 게이트에서 제외
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        null, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId)
        .containsExactlyInAnyOrder(female.getId(), male.getId());
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

  @Test
  @DisplayName("공개 게이트(#72): 사진 0장 피드는 온보딩 완료여도 후보에서 제외한다")
  void findCandidates_excludesFeedWithoutImages() {
    Feed ready = persistOnboardedFeed("ready", Gender.FEMALE);
    persistFeedOnly("noimg", Gender.FEMALE); // 이미지 0장
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(ready.getId());
  }

  @Test
  @DisplayName("공개 게이트(#72): 사진 5장 미만(전부 READY)이면 후보에서 제외한다")
  void findCandidates_excludesFewerThanFive() {
    Feed ready = persistOnboardedFeed("ready", Gender.FEMALE);
    Feed four = persistFeedOnly("four", Gender.FEMALE);
    persistImages(four, 4, 4); // 4장 전부 READY
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(ready.getId());
  }

  @Test
  @DisplayName("공개 게이트(#72): 5장이라도 하나가 PROCESSING이면 후보에서 제외한다")
  void findCandidates_excludesWhenAnyNotReady() {
    Feed ready = persistOnboardedFeed("ready", Gender.FEMALE);
    Feed mixed = persistFeedOnly("mixed", Gender.FEMALE);
    persistImages(mixed, 5, 4); // 4 READY + 1 PROCESSING
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(ready.getId());
  }

  @Test
  @DisplayName("공개 게이트(#72): 정확히 5장 전부 READY인 피드만 후보가 된다")
  void findCandidates_includesExactlyFiveReady() {
    Feed ready = persistOnboardedFeed("ready", Gender.FEMALE);
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    assertThat(result).extracting(Feed::getId).containsExactly(ready.getId());
  }

  @Test
  @DisplayName("MBTI 모름(#76): mbti=null 피드도 정상 적재·조회되어 재분배 후보가 된다")
  void findCandidates_includesNullMbtiCandidate() {
    Feed unknownMbti = persistFeedOnly("nombti", Gender.FEMALE, null);
    persistImages(unknownMbti, 5, 5); // 공개 게이트 통과
    em.flush();
    em.clear();

    List<Feed> result = discoveryRepository.findCandidates(
        Gender.FEMALE, NO_VIEWER, PageRequest.ofSize(10));

    // null mbti가 NOT NULL 위반 없이 적재되고, 조회 결과에 mbti=null로 그대로 실린다(점수 재분배 입력).
    assertThat(result).extracting(Feed::getId).containsExactly(unknownMbti.getId());
    assertThat(result.get(0).getMbti()).isNull();
  }
}
