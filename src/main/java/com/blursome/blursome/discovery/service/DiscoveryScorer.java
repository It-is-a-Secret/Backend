package com.blursome.blursome.discovery.service;

import com.blursome.blursome.keyword.domain.RelationType;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Mbti;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 탐색 점수 계산기(설계 §17.2~17.3). 순수 함수로 구성해 단위 테스트가 쉽다.
 *
 * <p>{@code Score = 0.35·R + 0.30·K + 0.20·M + 0.10·B + 0.05·D}(각 0~1). 활동성 R을 관심사 키워드 K보다
 * 높게 둬 최근 접속자를 앞세운다(#136). 한쪽이라도 MBTI를 모르면 M을 빼고 20%를 나머지에 비례 재분배한다
 * (R:K:B:D = 0.35:0.30:0.10:0.05, 합 0.80). 동점 타이브레이커는 정렬 단계(서비스)에서 적용한다.
 *
 * <p>K 정규화는 v1 휴리스틱이다: raw를 {@code [0, K_NORMALIZATION_CEILING]}으로 클램프 후 나눈다.
 * "카테고리당 상한"의 정밀 튜닝은 후속 과제(설계 §4·§8의 🧩)로 남긴다.
 */
@Component
public class DiscoveryScorer {

  static final double WEIGHT_ACTIVITY = 0.35;
  static final double WEIGHT_KEYWORD = 0.30;
  static final double WEIGHT_MBTI = 0.20;
  static final double WEIGHT_BIRTH_YEAR = 0.10;
  static final double WEIGHT_DEPARTMENT = 0.05;
  /** MBTI 제외 시 재분배 분모(= R+K+B+D 가중치 합). */
  static final double WEIGHT_WITHOUT_MBTI =
      WEIGHT_ACTIVITY + WEIGHT_KEYWORD + WEIGHT_BIRTH_YEAR + WEIGHT_DEPARTMENT;

  static final int SAME_TAG_SCORE = 10;
  static final int DIVERSITY_BONUS = 5;
  static final int DIVERSITY_CATEGORY_THRESHOLD = 3;
  static final int MAX_YEAR_DELTA = 10;
  static final double K_NORMALIZATION_CEILING = 50.0;
  /** 활동성 R이 0이 되는 경과 시간. 이보다 오래 접속하지 않았으면 R=0. */
  static final Duration ACTIVITY_WINDOW = Duration.ofDays(7);

  /**
   * viewer-후보 종합 점수(0~1 범위 합산).
   *
   * @param candidateLastActiveAt 후보의 마지막 접속 시각(없으면 {@code null} → R=0)
   * @param now                   경과 시간 기준 시각(한 요청 안의 후보들이 같은 기준을 쓰도록 호출 측이 넘긴다)
   */
  public double score(DiscoveryProfile viewer, DiscoveryProfile candidate,
      LocalDateTime candidateLastActiveAt, LocalDateTime now,
      Map<Long, Long> tagToCategory, RelationLookup relations) {
    double r = activityScore(candidateLastActiveAt, now);
    double k = keywordScore(viewer.tagIds(), candidate.tagIds(), tagToCategory, relations);
    double b = birthYearScore(viewer.birthYear(), candidate.birthYear());
    double d = departmentScore(viewer.department(), candidate.department());

    boolean mbtiKnown = viewer.mbti() != null && candidate.mbti() != null;
    if (!mbtiKnown) {
      return (WEIGHT_ACTIVITY * r + WEIGHT_KEYWORD * k + WEIGHT_BIRTH_YEAR * b
          + WEIGHT_DEPARTMENT * d) / WEIGHT_WITHOUT_MBTI;
    }
    double m = mbtiScore(viewer.mbti(), candidate.mbti());
    return WEIGHT_ACTIVITY * r + WEIGHT_KEYWORD * k + WEIGHT_MBTI * m
        + WEIGHT_BIRTH_YEAR * b + WEIGHT_DEPARTMENT * d;
  }

  /**
   * R: {@code 1 − min(경과, 7일)/7일}. 방금 접속=1.0, 7일 이상 미접속·기록 없음=0. 시계 오차로 접속 시각이 기준보다
   * 미래면 경과 0으로 본다.
   */
  double activityScore(LocalDateTime lastActiveAt, LocalDateTime now) {
    if (lastActiveAt == null) {
      return 0.0;
    }
    long windowSeconds = ACTIVITY_WINDOW.toSeconds();
    long elapsed = Math.max(0, Duration.between(lastActiveAt, now).toSeconds());
    return 1.0 - (double) Math.min(elapsed, windowSeconds) / windowSeconds;
  }

  /** K: 동일+10/유사+5/보완+3/충돌−4 + 다양성(+5) 합산 후 0~1 정규화. */
  double keywordScore(Set<Long> viewerTags, Set<Long> candTags,
      Map<Long, Long> tagToCategory, RelationLookup relations) {
    if (viewerTags.isEmpty() || candTags.isEmpty()) {
      return 0.0;
    }
    int raw = 0;
    Set<Long> positiveCategories = new HashSet<>();

    for (Long tag : viewerTags) {
      if (candTags.contains(tag)) {
        raw += SAME_TAG_SCORE;
        addCategory(positiveCategories, tagToCategory, tag);
      }
    }

    Set<Long> seenPairs = new HashSet<>();
    for (Long a : viewerTags) {
      for (Long c : candTags) {
        if (a.equals(c) || !seenPairs.add(pairKey(a, c))) {
          continue;
        }
        RelationType relation = relations.find(a, c);
        if (relation == null) {
          continue;
        }
        raw += relation.getScore();
        if (relation.getScore() > 0) {
          addCategory(positiveCategories, tagToCategory, a);
          addCategory(positiveCategories, tagToCategory, c);
        }
      }
    }

    if (positiveCategories.size() >= DIVERSITY_CATEGORY_THRESHOLD) {
      raw += DIVERSITY_BONUS;
    }
    return normalize(raw);
  }

  /** M: 동일=1.0, 그 외=0.0(궁합표 미도입). 호출 측이 모름 여부(재분배)를 판단한다. */
  double mbtiScore(Mbti viewer, Mbti candidate) {
    return viewer == candidate ? 1.0 : 0.0;
  }

  /** B: {@code 1 − min(|Δ년|, MAX_YEAR_DELTA)/MAX_YEAR_DELTA}. */
  double birthYearScore(int viewer, int candidate) {
    int delta = Math.min(Math.abs(viewer - candidate), MAX_YEAR_DELTA);
    return 1.0 - (double) delta / MAX_YEAR_DELTA;
  }

  /** D: 동일 학과=1.0 / 동일 계열=0.5 / 그 외=0. */
  double departmentScore(Department viewer, Department candidate) {
    if (viewer == candidate) {
      return 1.0;
    }
    return viewer.getCollege() == candidate.getCollege() ? 0.5 : 0.0;
  }

  private static double normalize(int raw) {
    int bounded = Math.max(0, Math.min(raw, (int) K_NORMALIZATION_CEILING));
    return bounded / K_NORMALIZATION_CEILING;
  }

  private static void addCategory(Set<Long> categories, Map<Long, Long> tagToCategory, Long tagId) {
    Long categoryId = tagToCategory.get(tagId);
    if (categoryId != null) {
      categories.add(categoryId);
    }
  }

  private static long pairKey(long a, long b) {
    long lo = Math.min(a, b);
    long hi = Math.max(a, b);
    return (lo << 32) | hi;
  }
}
