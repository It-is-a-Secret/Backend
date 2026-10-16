package com.blursome.blursome.keyword.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.exception.KeywordErrorCode;
import com.blursome.blursome.keyword.repository.KeywordCategoryRepository;
import com.blursome.blursome.keyword.repository.KeywordTagRepository;
import com.blursome.blursome.keyword.repository.MemberKeywordRepository;
import com.blursome.blursome.member.domain.Member;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원의 관심사 키워드 선택({@link MemberKeyword})을 검증·저장한다. 온보딩 최초 선택과 프로필 수정(#95)의
 * 재선택이 같은 규칙(태그 존재·활성, 필수 카테고리 각 최소 1개)을 쓰도록 한곳에 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberKeywordService {

  private final KeywordTagRepository keywordTagRepository;
  private final KeywordCategoryRepository keywordCategoryRepository;
  private final MemberKeywordRepository memberKeywordRepository;

  /** 회원이 선택한 키워드를 태그·카테고리와 함께 조회한다. */
  public List<MemberKeyword> findSelections(Long memberId) {
    return memberKeywordRepository.findByMemberIdWithTag(memberId);
  }

  /**
   * 요청 태그 id를 중복 제거 후 조회하고, 필수 카테고리 충족을 검증한다.
   *
   * <p>새로 고르는 태그는 활성이어야 한다. 다만 {@code retainedTagIds}(회원이 이미 선택 중인 태그)는 이후
   * 카탈로그에서 비활성화됐더라도 유지할 수 있다 — 운영 측 비활성화가 다른 항목 수정까지 막지 않도록 한다.
   *
   * @throws BaseException 존재하지 않거나 새로 고른 태그가 비활성인 경우, 필수 카테고리가 누락된 경우
   */
  public List<KeywordTag> resolveSelection(Collection<Long> tagIds, Set<Long> retainedTagIds) {
    List<Long> distinctIds = tagIds.stream().distinct().toList();
    List<KeywordTag> tags = keywordTagRepository.findAllById(distinctIds);
    boolean hasUnselectableTag = tags.stream()
        .anyMatch(tag -> !tag.isActive() && !retainedTagIds.contains(tag.getId()));
    if (tags.size() != distinctIds.size() || hasUnselectableTag) {
      throw BaseException.from(KeywordErrorCode.KEYWORD_TAG_NOT_FOUND);
    }
    validateRequiredCategories(tags);
    return tags;
  }

  /** 온보딩 최초 선택을 저장한다. {@link #resolveSelection}으로 검증한 태그를 받는다. */
  @Transactional
  public void saveInitialSelection(Member member, List<KeywordTag> tags) {
    memberKeywordRepository.saveAll(tags.stream().map(tag -> MemberKeyword.of(member, tag)).toList());
  }

  /**
   * 현재 선택을 {@code tags}로 교체한다(#95). 빠진 태그 행만 삭제하고 새 태그 행만 추가한다 — 전체 삭제 후
   * 재삽입하면 Hibernate flush가 INSERT를 DELETE보다 먼저 실행해 {@code uk_member_keyword}와 충돌하기 때문이다.
   */
  @Transactional
  public void replaceSelection(Member member, List<MemberKeyword> current, List<KeywordTag> tags) {
    Set<Long> currentTagIds = tagIdsOf(current);
    Set<Long> requestedTagIds = tags.stream().map(KeywordTag::getId).collect(Collectors.toSet());

    List<MemberKeyword> removed = current.stream()
        .filter(mk -> !requestedTagIds.contains(mk.getTag().getId()))
        .toList();
    List<MemberKeyword> added = tags.stream()
        .filter(tag -> !currentTagIds.contains(tag.getId()))
        .map(tag -> MemberKeyword.of(member, tag))
        .toList();

    memberKeywordRepository.deleteAll(removed);
    memberKeywordRepository.saveAll(added);
  }

  /** 선택 목록의 태그 id 집합. */
  public static Set<Long> tagIdsOf(List<MemberKeyword> selections) {
    return selections.stream().map(mk -> mk.getTag().getId()).collect(Collectors.toSet());
  }

  /** 필수 카테고리({@code is_required})마다 최소 1개 태그가 선택되었는지 검증한다. */
  private void validateRequiredCategories(List<KeywordTag> selectedTags) {
    Set<Long> selectedCategoryIds = selectedTags.stream()
        .map(tag -> tag.getCategory().getId())
        .collect(Collectors.toSet());
    List<KeywordCategory> requiredCategories = keywordCategoryRepository.findByRequiredTrue();
    boolean allCovered = requiredCategories.stream()
        .allMatch(category -> selectedCategoryIds.contains(category.getId()));
    if (!allCovered) {
      throw BaseException.from(KeywordErrorCode.KEYWORD_REQUIRED_CATEGORY_MISSING);
    }
  }
}
