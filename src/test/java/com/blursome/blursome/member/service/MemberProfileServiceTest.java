package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.service.MemberKeywordService;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.domain.ProfileItem;
import com.blursome.blursome.member.dto.request.ProfileUpdateRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.dto.response.NickNameAvailabilityResponse;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberProfileServiceTest {

  private static final Long MEMBER_ID = 1L;

  @Mock
  private MemberService memberService;
  @Mock
  private MemberRepository memberRepository;
  @Mock
  private FeedService feedService;
  @Mock
  private MemberKeywordService memberKeywordService;

  @InjectMocks
  private MemberProfileService memberProfileService;

  private Member member;
  private Feed feed;
  private List<MemberKeyword> currentKeywords;

  @BeforeEach
  void setUp() {
    member = Member.createOAuthMember(OAuthProvider.KAKAO, "pid", "name", "e@e.com", null);
    ReflectionTestUtils.setField(member, "id", MEMBER_ID);
    member.verifySchoolEmail("blur@gs.anyang.ac.kr");
    member.completeOnboarding("old-nick");
    feed = Feed.createOnOnboarding(
        member, Gender.MALE, 2000, Department.COMPUTER_ENGINEERING, Mbti.INTJ);
    currentKeywords = List.of(MemberKeyword.of(member, tag(10L)), MemberKeyword.of(member, tag(11L)));
  }

  private KeywordTag tag(long id) {
    KeywordCategory category = KeywordCategory.of("C", "카테고리", 1, true);
    ReflectionTestUtils.setField(category, "id", 1L);
    KeywordTag tag = KeywordTag.of(category, "T" + id, "태그" + id);
    ReflectionTestUtils.setField(tag, "id", id);
    return tag;
  }

  private void givenOnboardedMember() {
    given(memberService.findActiveMemberForUpdate(MEMBER_ID)).willReturn(member);
    given(feedService.findFeedByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(memberKeywordService.findSelections(MEMBER_ID)).willReturn(currentKeywords);
  }

  private void markChanged(ProfileItem item, LocalDateTime at) {
    ReflectionTestUtils.setField(member, "profileEditHistory",
        member.profileEditHistory().record(item, at));
  }

  @Test
  @DisplayName("전체를 보내도 바뀐 항목(MBTI)만 반영하고 그 항목의 변경일만 갱신한다")
  void updateProfile_appliesOnlyChangedItem() {
    givenOnboardedMember();

    MemberProfileResponse response = memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", Mbti.ENFP, List.of(11L, 10L)));

    assertThat(feed.getMbti()).isEqualTo(Mbti.ENFP);
    assertThat(response.profileEdit().mbti().lastChangedAt()).isNotNull();
    assertThat(response.profileEdit().mbti().changeable()).isFalse();
    assertThat(response.profileEdit().nickName().lastChangedAt()).isNull();
    assertThat(response.profileEdit().keywords().lastChangedAt()).isNull();
    verify(memberService, never()).isNickNameTaken(any(), any());
    verify(memberKeywordService, never()).replaceSelection(any(), anyList(), anyList());
  }

  @Test
  @DisplayName("MBTI를 '모름'(null)으로 바꾸는 것도 변경으로 반영한다")
  void updateProfile_mbtiToUnknown() {
    givenOnboardedMember();

    memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", null, List.of(10L, 11L)));

    assertThat(feed.getMbti()).isNull();
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.MBTI)).isNotNull();
  }

  @Test
  @DisplayName("닉네임 변경 시 회원·피드 닉네임을 함께 바꾸고 변경일을 기록한다")
  void updateProfile_changesNickNameAndSyncsFeed() {
    givenOnboardedMember();
    given(memberService.isNickNameTaken("new-nick", MEMBER_ID)).willReturn(false);

    memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("new-nick", Mbti.INTJ, List.of(10L, 11L)));

    assertThat(member.getNickName()).isEqualTo("new-nick");
    assertThat(feed.getNickName()).isEqualTo("new-nick");
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.NICKNAME)).isNotNull();
    verify(memberRepository).flush();
  }

  @Test
  @DisplayName("다른 회원이 쓰는 닉네임이면 409로 거절하고 아무것도 바꾸지 않는다")
  void updateProfile_duplicatedNickName() {
    givenOnboardedMember();
    given(memberService.isNickNameTaken("taken", MEMBER_ID)).willReturn(true);

    assertThatThrownBy(() -> memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("taken", Mbti.ENFP, List.of(10L, 11L))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NICKNAME_DUPLICATED.getCode());
    assertThat(member.getNickName()).isEqualTo("old-nick");
    assertThat(feed.getMbti()).isEqualTo(Mbti.INTJ);
  }

  @Test
  @DisplayName("사전 검사를 통과해도 저장 시 유니크 충돌(경합)이면 닉네임 중복으로 변환한다")
  void updateProfile_nickNameUniqueViolationOnFlush() {
    givenOnboardedMember();
    given(memberService.isNickNameTaken("racy", MEMBER_ID)).willReturn(false);
    willThrow(new DataIntegrityViolationException("uk_member_nickname"))
        .given(memberRepository).flush();

    assertThatThrownBy(() -> memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("racy", Mbti.INTJ, List.of(10L, 11L))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NICKNAME_DUPLICATED.getCode());
  }

  @Test
  @DisplayName("바뀐 항목 중 하나라도 30일 제한이면 전체를 거절한다(부분 반영 없음)")
  void updateProfile_rejectsAllWhenAnyChangedItemIsLimited() {
    givenOnboardedMember();
    markChanged(ProfileItem.KEYWORDS, LocalDateTime.now().minusDays(3));

    assertThatThrownBy(() -> memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", Mbti.ENFP, List.of(10L, 12L))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            MemberErrorCode.MEMBER_KEYWORDS_CHANGE_LIMITED.getCode());
    assertThat(feed.getMbti()).isEqualTo(Mbti.INTJ);
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.MBTI)).isNull();
  }

  @Test
  @DisplayName("제한 중인 항목도 값이 그대로면 변경이 아니므로 다른 항목 수정을 막지 않는다")
  void updateProfile_unchangedLimitedItemDoesNotBlock() {
    givenOnboardedMember();
    markChanged(ProfileItem.NICKNAME, LocalDateTime.now().minusDays(1));

    memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", Mbti.ENFP, List.of(10L, 11L)));

    assertThat(feed.getMbti()).isEqualTo(Mbti.ENFP);
  }

  @Test
  @DisplayName("관심사가 바뀌면 기존 선택을 유지 가능 태그로 넘겨 검증·교체하고 변경일을 기록한다")
  void updateProfile_replacesKeywords() {
    givenOnboardedMember();
    List<KeywordTag> resolved = List.of(tag(10L), tag(12L));
    given(memberKeywordService.resolveSelection(List.of(10L, 12L), java.util.Set.of(10L, 11L)))
        .willReturn(resolved);

    memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", Mbti.INTJ, List.of(10L, 12L)));

    verify(memberKeywordService).replaceSelection(member, currentKeywords, resolved);
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.KEYWORDS)).isNotNull();
  }

  @Test
  @DisplayName("바뀐 항목이 없으면 아무것도 갱신하지 않는다")
  void updateProfile_noChanges() {
    givenOnboardedMember();

    MemberProfileResponse response = memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("old-nick", Mbti.INTJ, List.of(10L, 11L, 10L)));

    assertThat(response.profileEdit().nickName().changeable()).isTrue();
    assertThat(response.profileEdit().mbti().changeable()).isTrue();
    assertThat(response.profileEdit().keywords().changeable()).isTrue();
    verify(memberKeywordService, never()).resolveSelection(anyCollection(), anySet());
    verify(memberRepository, never()).flush();
  }

  @Test
  @DisplayName("온보딩 전(피드 없음) 회원은 프로필을 수정할 수 없다")
  void updateProfile_requiresOnboarding() {
    given(memberService.findActiveMemberForUpdate(MEMBER_ID)).willReturn(member);
    given(feedService.findFeedByMemberId(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberProfileService.updateProfile(MEMBER_ID,
        new ProfileUpdateRequest("x", null, List.of(10L))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_ONBOARDING_REQUIRED.getCode());
  }

  @Test
  @DisplayName("닉네임 확인은 본인을 제외하고 중복 여부를 판단한다")
  void checkNickNameAvailability() {
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(member);
    given(memberService.isNickNameTaken("old-nick", MEMBER_ID)).willReturn(false);
    given(memberService.isNickNameTaken("taken", MEMBER_ID)).willReturn(true);

    NickNameAvailabilityResponse own =
        memberProfileService.checkNickNameAvailability(MEMBER_ID, "old-nick");
    NickNameAvailabilityResponse taken =
        memberProfileService.checkNickNameAvailability(MEMBER_ID, "taken");

    assertThat(own.available()).isTrue();
    assertThat(taken.available()).isFalse();
  }
}
