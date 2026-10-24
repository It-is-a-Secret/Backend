package com.blursome.blursome.feed.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link FeedImageRepository}의 실제 매핑·제약·쿼리 동작을 MySQL(Testcontainers)로 검증한다.
 * {@code @ManyToOne(LAZY)} 매핑, {@code uk_feed_image_order} 유니크 제약, 정렬 조회, 폴링 쿼리를 다룬다.
 * 저장 시 순서 교환·삭제 반영 순서는 {@code FeedImagePipelineIntegrationTest}가 실제 서비스로 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class FeedImageRepositoryTest {

  @Autowired
  private FeedImageRepository feedImageRepository;

  @Autowired
  private TestEntityManager em;

  @Test
  @DisplayName("findByFeedIdOrderByDisplayOrderAsc는 displayOrder 오름차순으로 조회한다")
  void findByFeedId_ordersByDisplayOrderAsc() {
    Feed feed = persistFeed("a");
    persistImage(feed, 3, FeedImageProcessingStatus.PROCESSING);
    persistImage(feed, 1, FeedImageProcessingStatus.PROCESSING);
    persistImage(feed, 2, FeedImageProcessingStatus.PROCESSING);
    em.flush();
    em.clear();

    List<FeedImage> images = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feed.getId());

    assertThat(images).extracting(FeedImage::getDisplayOrder).containsExactly(1, 2, 3);
    // @ManyToOne(LAZY) 매핑이 정상 동작해 연관 피드를 식별할 수 있어야 한다.
    assertThat(images).allMatch(image -> image.getFeed().getId().equals(feed.getId()));
  }

  @Test
  @DisplayName("같은 피드에 동일한 displayOrder를 저장하면 유니크 제약(uk_feed_image_order) 위반이다")
  void uniqueConstraint_rejectsDuplicateOrderInSameFeed() {
    Feed feed = persistFeed("dup");
    feedImageRepository.saveAndFlush(
        FeedImage.create(feed, 1, "originals/a.jpg", "variants/a.jpg", 80));

    FeedImage duplicate =
        FeedImage.create(feed, 1, "originals/b.jpg", "variants/b.jpg", 80);

    assertThatThrownBy(() -> feedImageRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("폴링 쿼리는 PROCESSING이면서 처리 시작<=threshold인 행만 처리 시작 오름차순으로 반환한다")
  void pollingQuery_filtersByStatusAndProcessingStartOrdered() {
    Feed feed = persistFeed("poll");
    LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);

    FeedImage older = persistImage(feed, 1, FeedImageProcessingStatus.PROCESSING);
    FeedImage newer = persistImage(feed, 2, FeedImageProcessingStatus.PROCESSING);
    FeedImage ready = persistImage(feed, 3, FeedImageProcessingStatus.READY);
    FeedImage reprocessed = persistImage(feed, 4, FeedImageProcessingStatus.PROCESSING);
    em.flush();

    overrideProcessingStartedAt(older, base);
    overrideProcessingStartedAt(newer, base.plusMinutes(2));
    overrideProcessingStartedAt(ready, base);
    // 오래전에 생성됐어도 최근에 재처리를 시작했다면 아직 확인 대상이 아니다.
    overrideCreatedAt(reprocessed, base.minusDays(1));
    overrideProcessingStartedAt(reprocessed, base.plusMinutes(10));
    em.flush();
    em.clear();

    List<FeedImage> result = feedImageRepository.findPollingTargets(
        FeedImageProcessingStatus.PROCESSING, base.plusMinutes(5), Limit.of(100));

    assertThat(result)
        .extracting(FeedImage::getId)
        .containsExactly(older.getId(), newer.getId());
  }

  @Test
  @DisplayName("처리 시작 시각이 없는 이전 행은 createdAt 기준으로 폴링 대상이 된다")
  void pollingQuery_fallsBackToCreatedAtForLegacyRows() {
    Feed feed = persistFeed("legacy");
    LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);

    FeedImage legacy = persistImage(feed, 1, FeedImageProcessingStatus.PROCESSING);
    FeedImage current = persistImage(feed, 2, FeedImageProcessingStatus.PROCESSING);
    em.flush();

    overrideCreatedAt(legacy, base.plusMinutes(1));
    overrideProcessingStartedAt(legacy, null);
    overrideProcessingStartedAt(current, base);
    em.flush();
    em.clear();

    List<FeedImage> result = feedImageRepository.findPollingTargets(
        FeedImageProcessingStatus.PROCESSING, base.plusMinutes(5), Limit.of(100));

    assertThat(result)
        .extracting(FeedImage::getId)
        .containsExactly(current.getId(), legacy.getId());
  }

  @Test
  @DisplayName("조건부 상태 전이는 id·variantKey·기대 상태가 모두 같을 때만 상태 컬럼만 바꾼다(#105)")
  void transitionProcessingStatus_updatesOnlyMatchingGeneration() {
    Feed feed = persistFeed("transition");
    FeedImage image = persistImage(feed, 1, FeedImageProcessingStatus.PROCESSING);
    em.flush();
    String currentVariant = image.getVariantKey();
    em.clear();

    // 스케줄러가 확인한 뒤 재처리로 key가 바뀐 경우(이전 세대) → 0건
    int staleGeneration = feedImageRepository.transitionProcessingStatus(image.getId(),
        "variants/old-generation.jpg", FeedImageProcessingStatus.PROCESSING,
        FeedImageProcessingStatus.READY);
    // 이미 다른 상태인 경우(기대 상태 불일치) → 0건
    int wrongExpected = feedImageRepository.transitionProcessingStatus(image.getId(),
        currentVariant, FeedImageProcessingStatus.FAILED, FeedImageProcessingStatus.READY);
    int matched = feedImageRepository.transitionProcessingStatus(image.getId(),
        currentVariant, FeedImageProcessingStatus.PROCESSING, FeedImageProcessingStatus.READY);
    em.clear();

    assertThat(staleGeneration).isZero();
    assertThat(wrongExpected).isZero();
    assertThat(matched).isEqualTo(1);
    FeedImage reloaded = feedImageRepository.findById(image.getId()).orElseThrow();
    assertThat(reloaded.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    assertThat(reloaded.getVariantKey()).isEqualTo(currentVariant);
    assertThat(reloaded.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("폴링 쿼리는 limit 개수까지만 반환한다")
  void pollingQuery_respectsLimit() {
    Feed feed = persistFeed("limit");
    persistImage(feed, 1, FeedImageProcessingStatus.PROCESSING);
    persistImage(feed, 2, FeedImageProcessingStatus.PROCESSING);
    em.flush();
    em.clear();

    List<FeedImage> result = feedImageRepository.findPollingTargets(
        FeedImageProcessingStatus.PROCESSING, LocalDateTime.now().plusMinutes(1), Limit.of(1));

    assertThat(result).hasSize(1);
  }

  private Feed persistFeed(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, "user-" + suffix + "@test.com",
        null);
    member.verifySchoolEmail("school-" + suffix + "@univ.ac.kr");
    member.completeOnboarding("nick-" + suffix);
    em.persist(member);

    Feed feed = Feed.createOnOnboarding(
        member, Gender.MALE, 2000, Department.COMPUTER_ENGINEERING, Mbti.INTJ);
    em.persist(feed);
    return feed;
  }

  private FeedImage persistImage(Feed feed, int displayOrder, FeedImageProcessingStatus status) {
    FeedImage image = FeedImage.create(
        feed, displayOrder,
        "originals/" + feed.getId() + "/" + displayOrder + ".jpg",
        "variants/" + feed.getId() + "/" + displayOrder + ".jpg",
        80);
    if (status == FeedImageProcessingStatus.READY) {
      image.markReady();
    } else if (status == FeedImageProcessingStatus.FAILED) {
      image.markFailed();
    }
    em.persist(image);
    return image;
  }

  /** {@code created_at}은 감사(@CreatedDate)로 채워지므로, 폴링 경계 검증을 위해 네이티브 UPDATE로 덮어쓴다. */
  private void overrideCreatedAt(FeedImage image, LocalDateTime createdAt) {
    em.getEntityManager()
        .createNativeQuery("UPDATE feed_image SET created_at = :ts WHERE id = :id")
        .setParameter("ts", createdAt)
        .setParameter("id", image.getId())
        .executeUpdate();
  }

  /** 폴링 경계 검증을 위해 {@code processing_started_at}을 네이티브 UPDATE로 덮어쓴다({@code null}이면 이전 행 재현). */
  private void overrideProcessingStartedAt(FeedImage image, LocalDateTime processingStartedAt) {
    em.getEntityManager()
        .createNativeQuery("UPDATE feed_image SET processing_started_at = :ts WHERE id = :id")
        .setParameter("ts", processingStartedAt)
        .setParameter("id", image.getId())
        .executeUpdate();
  }
}
