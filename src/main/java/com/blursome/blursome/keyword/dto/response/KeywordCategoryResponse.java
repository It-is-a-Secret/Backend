package com.blursome.blursome.keyword.dto.response;

import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import java.util.List;

/** 키워드 카테고리 한 건 + 소속 태그 목록 응답(온보딩 선택 화면용). */
public record KeywordCategoryResponse(
    Long id,
    String code,
    String name,
    int sortOrder,
    boolean required,
    List<KeywordTagResponse> tags
) {

  public static KeywordCategoryResponse of(KeywordCategory category, List<KeywordTag> tags) {
    return new KeywordCategoryResponse(
        category.getId(),
        category.getCode(),
        category.getName(),
        category.getSortOrder(),
        category.isRequired(),
        tags.stream().map(KeywordTagResponse::from).toList()
    );
  }
}
