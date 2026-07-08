package com.blursome.blursome.member.dto.response;

import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;

/** 회원이 선택한 키워드 한 건 응답(카테고리 + 태그). */
public record MemberKeywordResponse(
    String categoryCode,
    String categoryName,
    Long tagId,
    String tagCode,
    String tagName
) {

  public static MemberKeywordResponse from(MemberKeyword memberKeyword) {
    KeywordTag tag = memberKeyword.getTag();
    KeywordCategory category = tag.getCategory();
    return new MemberKeywordResponse(
        category.getCode(),
        category.getName(),
        tag.getId(),
        tag.getCode(),
        tag.getName()
    );
  }
}
