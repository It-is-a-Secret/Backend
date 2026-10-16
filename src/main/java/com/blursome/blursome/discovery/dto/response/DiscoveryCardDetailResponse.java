package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;

/**
 * 탐색 카드 상세(#95, {@code GET /api/discovery/feeds/{feedId}}). 카드 기본 정보에 상대의 관심사 키워드를 더한다.
 *
 * <p>목록 카드({@link DiscoveryCardResponse})와 마찬가지로 공개 프로필({@link Feed})만 담는다. 사진은
 * {@code GET /api/feeds/{feedId}/images}로 따로 조회한다.
 */
public record DiscoveryCardDetailResponse(
    Long feedId,
    String nickName,
    Integer birthYear,
    Department department,
    String departmentLabel,
    Mbti mbti,
    Gender gender,
    List<Keyword> keywords
) {

  /**
   * @param viewerTagIds 조회자가 선택한 태그 id — 겹치는 키워드에 {@code matched=true}를 표시한다
   */
  public static DiscoveryCardDetailResponse of(
      Feed feed, List<MemberKeyword> keywords, Set<Long> viewerTagIds) {
    return new DiscoveryCardDetailResponse(
        feed.getId(),
        feed.getNickName(),
        feed.getBirthYear(),
        feed.getDepartment(),
        feed.getDepartment().getLabel(),
        feed.getMbti(),
        feed.getGender(),
        keywords.stream().map(mk -> Keyword.of(mk, viewerTagIds)).toList()
    );
  }

  /** 상대가 선택한 키워드 한 건. {@code matched}는 조회자도 같은 태그를 선택했는지 여부. */
  @Schema(name = "DiscoveryCardKeywordItem", description = "상대가 선택한 키워드 한 건")
  public record Keyword(
      String categoryCode,
      String categoryName,
      Long tagId,
      String tagCode,
      String tagName,
      boolean matched
  ) {

    static Keyword of(MemberKeyword memberKeyword, Set<Long> viewerTagIds) {
      KeywordTag tag = memberKeyword.getTag();
      KeywordCategory category = tag.getCategory();
      return new Keyword(
          category.getCode(),
          category.getName(),
          tag.getId(),
          tag.getCode(),
          tag.getName(),
          viewerTagIds.contains(tag.getId())
      );
    }
  }
}
