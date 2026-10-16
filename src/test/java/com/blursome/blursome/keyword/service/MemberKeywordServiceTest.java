package com.blursome.blursome.keyword.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.exception.KeywordErrorCode;
import com.blursome.blursome.keyword.repository.KeywordCategoryRepository;
import com.blursome.blursome.keyword.repository.KeywordTagRepository;
import com.blursome.blursome.keyword.repository.MemberKeywordRepository;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberKeywordServiceTest {

  @Mock
  private KeywordTagRepository keywordTagRepository;
  @Mock
  private KeywordCategoryRepository keywordCategoryRepository;
  @Mock
  private MemberKeywordRepository memberKeywordRepository;

  @InjectMocks
  private MemberKeywordService memberKeywordService;

  private final KeywordCategory required = category(1L, true);

  private static KeywordCategory category(long id, boolean required) {
    KeywordCategory category = KeywordCategory.of("C" + id, "카테고리" + id, (int) id, required);
    ReflectionTestUtils.setField(category, "id", id);
    return category;
  }

  private KeywordTag tag(long id, boolean active) {
    KeywordTag tag = KeywordTag.of(required, "T" + id, "태그" + id);
    ReflectionTestUtils.setField(tag, "id", id);
    ReflectionTestUtils.setField(tag, "active", active);
    return tag;
  }

  @Test
  @DisplayName("이미 선택 중인 태그는 비활성화됐어도 유지할 수 있다")
  void resolveSelection_allowsRetainedInactiveTag() {
    KeywordTag retiredButKept = tag(10L, false);
    KeywordTag fresh = tag(11L, true);
    given(keywordTagRepository.findAllById(List.of(10L, 11L)))
        .willReturn(List.of(retiredButKept, fresh));
    given(keywordCategoryRepository.findByRequiredTrue()).willReturn(List.of(required));

    List<KeywordTag> result = memberKeywordService.resolveSelection(List.of(10L, 11L), Set.of(10L));

    assertThat(result).containsExactly(retiredButKept, fresh);
  }

  @Test
  @DisplayName("새로 고르는 태그가 비활성이면 거절한다")
  void resolveSelection_rejectsNewInactiveTag() {
    given(keywordTagRepository.findAllById(List.of(12L))).willReturn(List.of(tag(12L, false)));

    assertThatThrownBy(() -> memberKeywordService.resolveSelection(List.of(12L), Set.of(10L)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", KeywordErrorCode.KEYWORD_TAG_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("필수 카테고리를 빠뜨리면 거절한다")
  void resolveSelection_requiresRequiredCategories() {
    given(keywordTagRepository.findAllById(List.of(10L))).willReturn(List.of(tag(10L, true)));
    given(keywordCategoryRepository.findByRequiredTrue())
        .willReturn(List.of(required, category(2L, true)));

    assertThatThrownBy(() -> memberKeywordService.resolveSelection(List.of(10L), Set.of()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            KeywordErrorCode.KEYWORD_REQUIRED_CATEGORY_MISSING.getCode());
  }

  @Test
  @DisplayName("교체는 빠진 태그만 삭제하고 새 태그만 추가한다(유지 태그는 건드리지 않음)")
  @SuppressWarnings("unchecked")
  void replaceSelection_appliesDiffOnly() {
    Member member = Member.createOAuthMember(OAuthProvider.KAKAO, "pid", "n", null, null);
    MemberKeyword kept = MemberKeyword.of(member, tag(10L, true));
    MemberKeyword removed = MemberKeyword.of(member, tag(11L, true));

    memberKeywordService.replaceSelection(member, List.of(kept, removed),
        List.of(tag(10L, true), tag(12L, true)));

    ArgumentCaptor<List<MemberKeyword>> deleted = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<MemberKeyword>> saved = ArgumentCaptor.forClass(List.class);
    verify(memberKeywordRepository).deleteAll(deleted.capture());
    verify(memberKeywordRepository).saveAll(saved.capture());
    assertThat(deleted.getValue()).containsExactly(removed);
    assertThat(saved.getValue()).extracting(mk -> mk.getTag().getId()).containsExactly(12L);
  }
}
