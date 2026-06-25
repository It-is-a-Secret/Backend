package com.blursome.blursome.discovery.service;

import com.blursome.blursome.keyword.domain.RelationType;

/**
 * 두 태그 간 관계 유형을 조회하는 함수형 추상화. 순서와 무관하게 동작해야 한다(쌍은 정규화됨).
 * 운영에서는 {@link KeywordRelationCache}가, 테스트에서는 람다가 구현을 제공한다.
 */
@FunctionalInterface
public interface RelationLookup {

  /** 두 태그 사이 관계 유형. 관계가 없으면 {@code null}. */
  RelationType find(long tagId1, long tagId2);
}
