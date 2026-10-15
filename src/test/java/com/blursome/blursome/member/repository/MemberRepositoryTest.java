package com.blursome.blursome.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.domain.ProfileItem;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** #95 프로필 수정용 매핑·쿼리(항목별 변경 이력 임베디드, 본인 제외 닉네임 중복, 쓰기 락 조회)를 MySQL로 검증한다. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class MemberRepositoryTest {

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private TestEntityManager em;

  private Member persistOnboarded(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name", suffix + "@test.com", null);
    member.verifySchoolEmail("school-" + suffix + "@univ.ac.kr");
    member.completeOnboarding("nick-" + suffix);
    return em.persist(member);
  }

  @Test
  @DisplayName("변경 이력 컬럼이 모두 NULL인 회원을 다시 읽어도 빈 이력으로 동작한다")
  void profileEditHistory_allNullColumns_loadsAsEmpty() {
    Long id = persistOnboarded("a").getId();
    em.flush();
    em.clear();

    Member loaded = memberRepository.findById(id).orElseThrow();

    assertThat(loaded.profileEditHistory().lastChangedAt(ProfileItem.NICKNAME)).isNull();
    assertThat(loaded.profileEditHistory().isChangeable(ProfileItem.NICKNAME, LocalDateTime.now()))
        .isTrue();
  }

  @Test
  @DisplayName("항목별 변경 시각이 저장되고, 바뀐 항목만 값이 생긴다")
  void profileEditHistory_persistsPerItem() {
    Member member = persistOnboarded("b");
    LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    member.changeNickName("renamed", now);
    em.flush();
    em.clear();

    Member loaded = memberRepository.findByIdForUpdate(member.getId()).orElseThrow();

    assertThat(loaded.getNickName()).isEqualTo("renamed");
    assertThat(loaded.profileEditHistory().lastChangedAt(ProfileItem.NICKNAME)).isEqualTo(now);
    assertThat(loaded.profileEditHistory().lastChangedAt(ProfileItem.MBTI)).isNull();
    assertThat(loaded.profileEditHistory().isChangeable(ProfileItem.NICKNAME, now.plusDays(1)))
        .isFalse();
  }

  @Test
  @DisplayName("닉네임 중복 확인은 본인을 제외한다")
  void existsByNickNameAndIdNot_excludesSelf() {
    Member a = persistOnboarded("c");
    Member b = persistOnboarded("d");
    em.flush();

    assertThat(memberRepository.existsByNickNameAndIdNot("nick-c", a.getId())).isFalse();
    assertThat(memberRepository.existsByNickNameAndIdNot("nick-c", b.getId())).isTrue();
    assertThat(memberRepository.existsByNickNameAndIdNot("unused", b.getId())).isFalse();
  }
}
