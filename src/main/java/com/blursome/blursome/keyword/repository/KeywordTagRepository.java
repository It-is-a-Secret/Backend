package com.blursome.blursome.keyword.repository;

import com.blursome.blursome.keyword.domain.KeywordTag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KeywordTagRepository extends JpaRepository<KeywordTag, Long> {

  /** 활성 태그를 카테고리·태그 정렬 순서로 조회한다(카탈로그 노출용, 카테고리 함께 페치). */
  @Query("select t from KeywordTag t join fetch t.category c "
      + "where t.active = true order by c.sortOrder asc, t.id asc")
  List<KeywordTag> findActiveTagsWithCategory();

  /** 코드 목록으로 태그를 조회한다(시드 관계 매핑용). */
  List<KeywordTag> findByCodeIn(List<String> codes);
}
