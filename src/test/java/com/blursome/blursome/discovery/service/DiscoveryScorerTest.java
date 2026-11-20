package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.keyword.domain.RelationType;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Mbti;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscoveryScorerTest {

  private static final double EPS = 1e-6;
  private static final RelationLookup NO_RELATION = (a, b) -> null;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

  private final DiscoveryScorer scorer = new DiscoveryScorer();

  // ---- K: 키워드 ----

  @Test
  @DisplayName("K: 동일 태그 1개 = raw 10 → 0.2")
  void keywordScore_sharedTag() {
    double k = scorer.keywordScore(Set.of(1L, 2L), Set.of(2L, 3L),
        Map.of(1L, 100L, 2L, 100L, 3L, 200L), NO_RELATION);
    assertThat(k).isCloseTo(0.2, org.assertj.core.api.Assertions.offset(EPS));
  }

  @Test
  @DisplayName("K: 유사 관계(+5) = raw 5 → 0.1")
  void keywordScore_similarRelation() {
    RelationLookup similar = (a, b) -> RelationType.SIMILAR;
    double k = scorer.keywordScore(Set.of(1L), Set.of(2L),
        Map.of(1L, 100L, 2L, 200L), similar);
    assertThat(k).isCloseTo(0.1, org.assertj.core.api.Assertions.offset(EPS));
  }

  @Test
  @DisplayName("K: 충돌(−4)만 있으면 raw 음수 → 0으로 클램프")
  void keywordScore_conflictClampedToZero() {
    RelationLookup conflict = (a, b) -> RelationType.CONFLICT;
    double k = scorer.keywordScore(Set.of(1L), Set.of(2L),
        Map.of(1L, 100L, 2L, 200L), conflict);
    assertThat(k).isZero();
  }

  @Test
  @DisplayName("K: 서로 다른 카테고리 3개 이상에서 + → 다양성 보너스 +5")
  void keywordScore_diversityBonus() {
    // 동일 태그 3개(서로 다른 카테고리) → raw 30 + 다양성 5 = 35 → 0.7
    double k = scorer.keywordScore(Set.of(1L, 2L, 3L), Set.of(1L, 2L, 3L),
        Map.of(1L, 100L, 2L, 200L, 3L, 300L), NO_RELATION);
    assertThat(k).isCloseTo(0.7, org.assertj.core.api.Assertions.offset(EPS));
  }

  @Test
  @DisplayName("K: 한쪽 태그가 비면 0")
  void keywordScore_emptyTags() {
    assertThat(scorer.keywordScore(Set.of(), Set.of(1L), Map.of(), NO_RELATION)).isZero();
  }

  // ---- M / B / D ----

  @Test
  @DisplayName("M: 동일 MBTI=1.0, 다르면 0.0")
  void mbtiScore() {
    assertThat(scorer.mbtiScore(Mbti.INTJ, Mbti.INTJ)).isEqualTo(1.0);
    assertThat(scorer.mbtiScore(Mbti.INTJ, Mbti.ENFP)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("B: |Δ년| 0→1.0, 5→0.5, 10 이상→0")
  void birthYearScore() {
    assertThat(scorer.birthYearScore(2000, 2000)).isEqualTo(1.0);
    assertThat(scorer.birthYearScore(2000, 1995)).isCloseTo(0.5, org.assertj.core.api.Assertions.offset(EPS));
    assertThat(scorer.birthYearScore(2000, 1990)).isEqualTo(0.0);
    assertThat(scorer.birthYearScore(2000, 1970)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("D: 동일 학과=1.0, 동일 계열=0.5, 그 외=0")
  void departmentScore() {
    assertThat(scorer.departmentScore(
        Department.COMPUTER_ENGINEERING, Department.COMPUTER_ENGINEERING)).isEqualTo(1.0);
    // 컴퓨터공학과·소프트웨어학과 = 같은 창의융합대학
    assertThat(scorer.departmentScore(
        Department.COMPUTER_ENGINEERING, Department.SOFTWARE)).isEqualTo(0.5);
    // 다른 계열(신학과)
    assertThat(scorer.departmentScore(
        Department.COMPUTER_ENGINEERING, Department.THEOLOGY)).isEqualTo(0.0);
  }

  // ---- R: 활동성(#136) ----

  @Test
  @DisplayName("R: 방금 접속=1.0, 3.5일 전=0.5, 7일 이상·기록 없음=0")
  void activityScore() {
    assertThat(scorer.activityScore(NOW, NOW)).isEqualTo(1.0);
    assertThat(scorer.activityScore(NOW.minusHours(84), NOW))
        .isCloseTo(0.5, org.assertj.core.api.Assertions.offset(EPS));
    assertThat(scorer.activityScore(NOW.minusDays(7), NOW)).isEqualTo(0.0);
    assertThat(scorer.activityScore(NOW.minusDays(30), NOW)).isEqualTo(0.0);
    assertThat(scorer.activityScore(null, NOW)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("R: 접속 시각이 기준보다 미래(시계 오차)면 1.0으로 본다")
  void activityScore_futureClampedToOne() {
    assertThat(scorer.activityScore(NOW.plusMinutes(5), NOW)).isEqualTo(1.0);
  }

  // ---- 종합 점수 ----

  @Test
  @DisplayName("종합: 활동성 가중치가 관심사 키워드보다 크다")
  void activityWeightOutranksKeyword() {
    assertThat(DiscoveryScorer.WEIGHT_ACTIVITY).isGreaterThan(DiscoveryScorer.WEIGHT_KEYWORD);
    assertThat(DiscoveryScorer.WEIGHT_ACTIVITY + DiscoveryScorer.WEIGHT_KEYWORD
        + DiscoveryScorer.WEIGHT_MBTI + DiscoveryScorer.WEIGHT_BIRTH_YEAR
        + DiscoveryScorer.WEIGHT_DEPARTMENT)
        .isCloseTo(1.0, org.assertj.core.api.Assertions.offset(EPS));
  }

  @Test
  @DisplayName("종합: 다른 조건이 같으면 키워드가 완전히 맞는 7일 미접속자보다 방금 접속한 키워드 불일치자가 앞선다")
  void score_recentActivityBeatsKeywordMatch() {
    DiscoveryProfile viewer = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L, 2L, 3L));
    DiscoveryProfile keywordMatch = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L, 2L, 3L));
    DiscoveryProfile noKeyword = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(9L));
    Map<Long, Long> categories = Map.of(1L, 100L, 2L, 200L, 3L, 300L, 9L, 900L);

    // K=0.7, R=0
    double stale = scorer.score(viewer, keywordMatch, NOW.minusDays(8), NOW, categories,
        NO_RELATION);
    // K=0, R=1.0
    double recent = scorer.score(viewer, noKeyword, NOW, NOW, categories, NO_RELATION);

    assertThat(recent).isGreaterThan(stale);
  }

  @Test
  @DisplayName("종합: MBTI 알면 0.35R+0.30K+0.20M+0.10B+0.05D")
  void score_withMbti() {
    DiscoveryProfile viewer = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L));
    DiscoveryProfile candidate = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L));
    // R=0.5(3.5일 전), K=0.2, M=1.0, B=1.0, D=1.0
    double expected = 0.35 * 0.5 + 0.30 * 0.2 + 0.20 * 1.0 + 0.10 * 1.0 + 0.05 * 1.0;

    double score = scorer.score(viewer, candidate, NOW.minusHours(84), NOW, Map.of(1L, 100L),
        NO_RELATION);

    assertThat(score).isCloseTo(expected, org.assertj.core.api.Assertions.offset(EPS));
  }

  @Test
  @DisplayName("종합: 한쪽 MBTI 모름이면 M 제외 후 20% 재분배")
  void score_redistributesWhenMbtiUnknown() {
    DiscoveryProfile viewer = new DiscoveryProfile(
        null, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L));
    DiscoveryProfile candidate = new DiscoveryProfile(
        Mbti.INTJ, 2000, Department.COMPUTER_ENGINEERING, Set.of(1L));
    // R=1.0, K=0.2, B=1.0, D=1.0 → (0.35*1 + 0.30*0.2 + 0.10*1 + 0.05*1)/0.80
    double expected = (0.35 * 1.0 + 0.30 * 0.2 + 0.10 * 1.0 + 0.05 * 1.0) / 0.80;

    double score = scorer.score(viewer, candidate, NOW, NOW, Map.of(1L, 100L), NO_RELATION);

    assertThat(score).isCloseTo(expected, org.assertj.core.api.Assertions.offset(EPS));
  }
}
