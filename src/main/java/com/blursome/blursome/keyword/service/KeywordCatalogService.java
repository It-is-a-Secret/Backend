package com.blursome.blursome.keyword.service;

import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.dto.response.KeywordCategoryResponse;
import com.blursome.blursome.keyword.repository.KeywordCategoryRepository;
import com.blursome.blursome.keyword.repository.KeywordTagRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 온보딩 키워드 선택 화면에 노출할 카탈로그(카테고리 + 활성 태그)를 조회한다.
 *
 * <p>참조 데이터라 변경 빈도가 낮으므로 단순 조회로 구성한다(필요 시 캐시는 후속 과제).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KeywordCatalogService {

  private final KeywordCategoryRepository keywordCategoryRepository;
  private final KeywordTagRepository keywordTagRepository;

  /** 정렬 순서대로 카테고리와 그 활성 태그를 묶어 반환한다. */
  public List<KeywordCategoryResponse> getCatalog() {
    List<KeywordCategory> categories = keywordCategoryRepository.findAllByOrderBySortOrderAsc();

    Map<Long, List<KeywordTag>> tagsByCategoryId = new LinkedHashMap<>();
    for (KeywordTag tag : keywordTagRepository.findActiveTagsWithCategory()) {
      tagsByCategoryId.computeIfAbsent(tag.getCategory().getId(), key -> new java.util.ArrayList<>())
          .add(tag);
    }

    return categories.stream()
        .map(category ->
            KeywordCategoryResponse.of(category, tagsByCategoryId.getOrDefault(category.getId(),
                List.of())))
        .toList();
  }
}
