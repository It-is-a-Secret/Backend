package com.blursome.blursome.keyword.dto.response;

import com.blursome.blursome.keyword.domain.KeywordTag;

/** 키워드 태그 한 건 응답(카탈로그 노출용). */
public record KeywordTagResponse(
    Long id,
    String code,
    String name
) {

  public static KeywordTagResponse from(KeywordTag tag) {
    return new KeywordTagResponse(tag.getId(), tag.getCode(), tag.getName());
  }
}
