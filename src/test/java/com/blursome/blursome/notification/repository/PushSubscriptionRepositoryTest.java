package com.blursome.blursome.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.notification.domain.PushSubscription;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link PushSubscriptionRepository}의 매핑·제약·쿼리를 MySQL(Testcontainers)로 검증한다(#121). 긴 endpoint 저장,
 * endpoint 해시 유니크, 소유자 조건 삭제, 결과 반영 쿼리를 다룬다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class PushSubscriptionRepositoryTest {

  @Autowired
  private PushSubscriptionRepository repository;

  @Autowired
  private TestEntityManager em;

  private Member persistMember(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    em.persist(member);
    return member;
  }

  private PushSubscription persistSubscription(Member member, String endpoint) {
    return repository.saveAndFlush(
        PushSubscription.register(member, endpoint, "p256dh", "auth", "UA"));
  }

  @Test
  @DisplayName("2048자 endpoint를 저장하고 해시로 다시 찾는다")
  void findByEndpointHash_whenLongEndpoint_thenFound() {
    Member member = persistMember("a");
    String endpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(2048 - 36);
    persistSubscription(member, endpoint);
    em.clear();

    assertThat(repository.findByEndpointHash(PushSubscription.hashEndpoint(endpoint)))
        .get().extracting(PushSubscription::getEndpoint).isEqualTo(endpoint);
  }

  @Test
  @DisplayName("같은 endpoint 두 행은 유니크 제약(uk_push_subscription_endpoint_hash)으로 막힌다")
  void save_whenDuplicateEndpoint_thenViolatesUnique() {
    Member a = persistMember("a");
    Member b = persistMember("b");
    persistSubscription(a, "https://web.push.apple.com/dup");

    assertThatThrownBy(() -> persistSubscription(b, "https://web.push.apple.com/dup"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("소유자 조건 삭제는 남의 구독을 지우지 않고, 회원별 조회·전체 삭제는 그 회원 것만 다룬다")
  void deleteQueries_respectOwner() {
    Member me = persistMember("me");
    Member other = persistMember("other");
    persistSubscription(me, "https://web.push.apple.com/me-1");
    persistSubscription(me, "https://fcm.googleapis.com/me-2");
    persistSubscription(other, "https://web.push.apple.com/other");

    int deletedByStranger = repository.deleteByEndpointHashAndMemberId(
        PushSubscription.hashEndpoint("https://web.push.apple.com/me-1"), other.getId());
    assertThat(deletedByStranger).isZero();
    assertThat(repository.findAllByMemberId(me.getId())).hasSize(2);

    assertThat(repository.deleteAllByMemberId(me.getId())).isEqualTo(2);
    assertThat(repository.findAllByMemberId(me.getId())).isEmpty();
    assertThat(repository.findAllByMemberId(other.getId())).hasSize(1);
  }

  @Test
  @DisplayName("발송 결과 반영: 성공 구독은 마지막 성공 시각이 남고, 만료 구독은 지워진다")
  void markDeliveredAndDeleteExpired() {
    Member me = persistMember("me");
    PushSubscription delivered = persistSubscription(me, "https://web.push.apple.com/ok");
    PushSubscription expired = persistSubscription(me, "https://web.push.apple.com/gone");
    LocalDateTime now = LocalDateTime.of(2026, 11, 15, 12, 0);

    repository.markDelivered(List.of(delivered.getId()), now);
    repository.deleteAllByIdIn(List.of(expired.getId()));

    assertThat(repository.findById(delivered.getId())).get()
        .extracting(PushSubscription::getLastSuccessAt).isEqualTo(now);
    assertThat(repository.findById(expired.getId())).isEmpty();
  }
}
