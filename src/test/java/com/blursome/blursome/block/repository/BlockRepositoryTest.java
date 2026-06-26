package com.blursome.blursome.block.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.support.TestcontainersConfiguration;
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
 * {@link BlockRepository}의 매핑·제약·쿼리를 MySQL(Testcontainers)로 검증한다.
 * 방향성 존재 조회, 해제(삭제), {@code uk_block_pair} 중복 차단 차단을 다룬다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class BlockRepositoryTest {

  @Autowired
  private BlockRepository blockRepository;

  @Autowired
  private TestEntityManager em;

  private Member persistMember(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    em.persist(member);
    return member;
  }

  @Test
  @DisplayName("차단 존재 조회는 방향성을 가진다(A→B 차단은 B→A로는 false)")
  void existsBy_isDirectional() {
    Member a = persistMember("a");
    Member b = persistMember("b");
    blockRepository.save(Block.of(a, b));
    em.flush();
    em.clear();

    assertThat(blockRepository.existsByBlockerIdAndBlockedId(a.getId(), b.getId())).isTrue();
    assertThat(blockRepository.existsByBlockerIdAndBlockedId(b.getId(), a.getId())).isFalse();
  }

  @Test
  @DisplayName("차단 해제는 해당 방향 행만 삭제한다")
  void deleteByPair_removesRow() {
    Member a = persistMember("a");
    Member b = persistMember("b");
    blockRepository.save(Block.of(a, b));
    em.flush();
    em.clear();

    long deleted = blockRepository.deleteByBlockerIdAndBlockedId(a.getId(), b.getId());

    assertThat(deleted).isEqualTo(1);
    assertThat(blockRepository.existsByBlockerIdAndBlockedId(a.getId(), b.getId())).isFalse();
  }

  @Test
  @DisplayName("양방향 차단 존재 조회는 어느 방향 차단이든 true다(#77)")
  void existsBlockBetween_isBidirectional() {
    Member a = persistMember("a");
    Member b = persistMember("b");
    Member c = persistMember("c");
    blockRepository.save(Block.of(a, b)); // a→b 단방향
    em.flush();
    em.clear();

    // 어느 방향으로 물어도 a↔b 사이엔 차단 존재
    assertThat(blockRepository.existsBlockBetween(a.getId(), b.getId())).isTrue();
    assertThat(blockRepository.existsBlockBetween(b.getId(), a.getId())).isTrue();
    // 차단이 없는 쌍은 false
    assertThat(blockRepository.existsBlockBetween(a.getId(), c.getId())).isFalse();
  }

  @Test
  @DisplayName("동일 (blocker, blocked) 중복 차단은 uk_block_pair 위반으로 막힌다")
  void duplicatePair_violatesUnique() {
    Member a = persistMember("a");
    Member b = persistMember("b");
    blockRepository.saveAndFlush(Block.of(a, b));

    assertThatThrownBy(() -> blockRepository.saveAndFlush(Block.of(a, b)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
