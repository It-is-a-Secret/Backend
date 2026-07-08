package com.blursome.blursome.keyword.repository;

import com.blursome.blursome.keyword.domain.KeywordRelation;
import com.blursome.blursome.keyword.domain.RelationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KeywordRelationRepository extends JpaRepository<KeywordRelation, Long> {

  /**
   * 모든 관계를 (정규화된 tag_a_id, tag_b_id, 유형) 투영으로 조회한다(탐색 K 점수의 메모리 캐시 적재용).
   * 엔티티/연관을 적재하지 않아 lazy 초기화 없이 가볍다.
   */
  @Query("select r.tagA.id as tagAId, r.tagB.id as tagBId, r.relationType as relationType "
      + "from KeywordRelation r")
  List<RelationPair> findAllPairs();

  /** {@link #findAllPairs()} 투영. 항상 {@code tagAId < tagBId}로 정규화되어 있다. */
  interface RelationPair {
    Long getTagAId();

    Long getTagBId();

    RelationType getRelationType();
  }
}
