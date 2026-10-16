package com.blursome.blursome.member.service;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.service.MemberKeywordService;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.ProfileItem;
import com.blursome.blursome.member.dto.request.ProfileUpdateRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.dto.response.NickNameAvailabilityResponse;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필 조회·수정과 닉네임 사용 가능 여부 확인을 담당하는 Facade 서비스(#95).
 *
 * <p>수정은 수정 가능 항목(닉네임·MBTI·관심사) 전체를 받아 현재 값과 비교하고, <b>실제로 바뀐 항목만</b>
 * 반영한다. 30일 변경 제한도 바뀐 항목에만 적용하며, 하나라도 제한에 걸리면 아무것도 반영하지 않는다(부분 반영
 * 없음). 항목별 값의 소유는 닉네임 {@link Member}(+{@link Feed} 비정규화 사본), MBTI {@link Feed}, 관심사
 * {@link MemberKeyword}이고, 변경 주기 정책은 {@link Member}가 보유한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileService {

  private final MemberService memberService;
  private final MemberRepository memberRepository;
  private final FeedService feedService;
  private final MemberKeywordService memberKeywordService;

  /** 내 프로필을 공개 프로필(Feed)·선택 키워드·항목별 변경 제한 상태와 함께 조회한다. 온보딩 미완료 회원은 Feed 필드가 null. */
  public MemberProfileResponse getMyProfile(Long memberId) {
    Member member = memberService.findActiveMember(memberId);
    Feed feed = feedService.findFeedByMemberId(memberId).orElse(null);
    return MemberProfileResponse.of(
        member, feed, memberKeywordService.findSelections(memberId), LocalDateTime.now());
  }

  /**
   * 닉네임 사용 가능 여부를 확인한다. 본인의 현재 닉네임은 사용 가능으로 본다. 온보딩 중(닉네임 입력 단계)에도
   * 쓰므로 온보딩 완료를 요구하지 않는다.
   */
  public NickNameAvailabilityResponse checkNickNameAvailability(Long memberId, String nickName) {
    memberService.findActiveMember(memberId);
    return new NickNameAvailabilityResponse(nickName,
        !memberService.isNickNameTaken(nickName, memberId));
  }

  /**
   * 프로필을 수정한다. 바뀐 항목만 반영하고 그 항목의 최근 변경 시각만 갱신한다. 바뀐 항목이 없으면 아무것도
   * 하지 않고 현재 프로필을 반환한다.
   *
   * <p>같은 회원의 동시 수정이 둘 다 30일 검증을 통과하지 않도록 회원 행에 쓰기 락을 건다. 닉네임은 사전 중복
   * 검사 후, 경합으로 인한 유니크 제약({@code uk_member_nickname}/{@code uk_feed_nick_name}) 충돌도 같은
   * 에러로 변환한다.
   *
   * @throws BaseException 온보딩 전, 바뀐 항목의 30일 제한, 닉네임 중복, 키워드가 유효하지 않거나 필수 카테고리
   *     누락인 경우
   */
  @Transactional
  public MemberProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request) {
    LocalDateTime now = LocalDateTime.now();
    Member member = memberService.findActiveMemberForUpdate(memberId);
    Feed feed = feedService.findFeedByMemberId(memberId)
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_ONBOARDING_REQUIRED));
    List<MemberKeyword> currentKeywords = memberKeywordService.findSelections(memberId);
    Set<Long> currentTagIds = MemberKeywordService.tagIdsOf(currentKeywords);

    Set<ProfileItem> changed = EnumSet.noneOf(ProfileItem.class);
    if (!request.nickName().equals(member.getNickName())) {
      changed.add(ProfileItem.NICKNAME);
    }
    if (!Objects.equals(request.mbti(), feed.getMbti())) {
      changed.add(ProfileItem.MBTI);
    }
    if (!Set.copyOf(request.keywordTagIds()).equals(currentTagIds)) {
      changed.add(ProfileItem.KEYWORDS);
    }

    // 반영 전에 바뀐 항목 전부를 검증한다 — 하나라도 막히면 아무것도 바꾸지 않는다.
    changed.forEach(item -> member.validateProfileChangeable(item, now));
    if (changed.contains(ProfileItem.NICKNAME)
        && memberService.isNickNameTaken(request.nickName(), memberId)) {
      throw BaseException.from(MemberErrorCode.MEMBER_NICKNAME_DUPLICATED);
    }
    List<KeywordTag> selectedTags = changed.contains(ProfileItem.KEYWORDS)
        ? memberKeywordService.resolveSelection(request.keywordTagIds(), currentTagIds)
        : List.of();

    if (changed.contains(ProfileItem.NICKNAME)) {
      member.changeNickName(request.nickName(), now);
      feed.updateNickName(request.nickName());
      try {
        memberRepository.flush();
      } catch (DataIntegrityViolationException e) {
        throw BaseException.from(MemberErrorCode.MEMBER_NICKNAME_DUPLICATED);
      }
    }
    if (changed.contains(ProfileItem.MBTI)) {
      member.recordProfileChange(ProfileItem.MBTI, now);
      feed.changeMbti(request.mbti());
    }
    if (changed.contains(ProfileItem.KEYWORDS)) {
      member.recordProfileChange(ProfileItem.KEYWORDS, now);
      memberKeywordService.replaceSelection(member, currentKeywords, selectedTags);
      currentKeywords = memberKeywordService.findSelections(memberId);
    }

    return MemberProfileResponse.of(member, feed, currentKeywords, now);
  }
}
