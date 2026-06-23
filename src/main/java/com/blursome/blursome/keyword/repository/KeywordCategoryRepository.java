package com.blursome.blursome.keyword.repository;

import com.blursome.blursome.keyword.domain.KeywordCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeywordCategoryRepository extends JpaRepository<KeywordCategory, Long> {

  /** 정렬 순서대로 전체 카테고리를 조회한다(카탈로그 노출용). */
  List<KeywordCategory> findAllByOrderBySortOrderAsc();

  /** 온보딩에서 최소 1개 선택이 필수인 카테고리만 조회한다. */
  List<KeywordCategory> findByRequiredTrue();
}
