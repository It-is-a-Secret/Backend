package com.blursome.blursome.member.service;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.domain.MemberKeyword;
import com.blursome.blursome.keyword.service.MemberKeywordService;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.dto.request.OnboardingRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.member.verification.EmailVerificationSender;
import com.blursome.blursome.member.verification.SchoolEmailPolicy;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore.VerificationEntry;
import com.blursome.blursome.member.verification.VerificationCodeGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 온보딩 플로우(학교 이메일 인증 → 프로필 작성)를 조율하는 Facade 서비스.
 *
 * <p>플로우는 {@code 인증 코드 발송 → 코드 검증(학교 인증 완료) → 온보딩 작성(프로필·키워드 저장)}으로
 * 진행되며, 가입 단계 전이의 불변식은 {@link Member} 도메인 메서드가, 유니크 충돌 변환은 본 서비스가
 * 담당한다. 온보딩 이후 프로필 조회·수정은 {@link MemberProfileService}가 담당한다(#95). 설계는
 * {@code docs/member/MEMBER_ONBOARDING.md}, {@code docs/keyword/KEYWORD_DOMAIN.md} 참조.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberOnboardingService {

  private final MemberService memberService;
  private final MemberRepository memberRepository;
  private final MemberKeywordService memberKeywordService;
  private final SchoolEmailPolicy schoolEmailPolicy;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final SchoolEmailVerificationStore verificationStore;
  private final EmailVerificationSender emailVerificationSender;
  private final FeedService feedService;

  /**
   * 학교 이메일로 6자리 인증 코드를 발송한다.
   *
   * <p>도메인 정책(@gs.anyang.ac.kr) 검증 후 코드를 생성해 Redis에 TTL과 함께 저장하고 발송한다.
   * 이미 학교 인증을 마친 회원이면 재발송하지 않는다. 이메일은 공백 제거·소문자로 정규화한 값을 쓰며, 다른 회원이
   * 사용 중이거나 탈퇴 후 30일 재가입 제한 중인 이메일이면 코드를 보내지 않는다(#107). 최종 저장 직전에도 다시
   * 검증한다({@link #verifySchoolEmail}).
   *
   * @throws BaseException 회원이 비활성이거나, 도메인이 부적합하거나, 이미 인증을 마쳤거나, 이메일이 사용 중
   *     ({@code MEMBER_409_SCHOOL_EMAIL_DUPLICATED})·재가입 제한 중({@code MEMBER_409_REJOIN_RESTRICTED})인 경우
   */
  public void sendSchoolEmailVerificationCode(Long memberId, String schoolEmail) {
    Member member = memberService.findActiveMember(memberId);
    if (member.isSchoolVerified()) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_VERIFIED);
    }
    String email = schoolEmailPolicy.normalize(schoolEmail);
    schoolEmailPolicy.validate(email);
    memberService.validateSchoolEmailAvailable(memberId, email);

    String code = verificationCodeGenerator.generate();
    verificationStore.save(memberId, email, code, SchoolEmailVerificationStore.CODE_TTL);
    emailVerificationSender.send(email, code);
  }

  /**
   * 인증 코드를 검증하고 학교 인증을 완료한다({@code UNVERIFIED → VERIFIED}).
   *
   * <p>저장된 코드·대상 이메일이 요청과 일치하면 {@link Member#verifySchoolEmail(String)}로 단계를
   * 전진시키고 코드를 폐기한다. 학교 이메일 유니크 충돌은 도메인 예외로 변환한다.
   *
   * <p>저장 직전에 이메일 보유자를 다시 확인한다(#107). 다른 활성 회원이 쓰면 중복, 탈퇴 후 30일 미만인 회원이 쥐고 있으면
   * 재가입 제한으로 거부하고, 30일이 지난 탈퇴 회원이면 정리 배치를 기다리지 않고 그 자리에서 해제한 뒤 저장한다. 본인 행을
   * 쓰기 락으로 읽어 같은 회원의 동시 탈퇴와 직렬화한다(탈퇴가 먼저 커밋되면 비활성으로 막힌다).
   *
   * @throws BaseException 코드 부재/만료, 이메일·코드 불일치, 단계 위반, 학교 이메일 중복·재가입 제한인 경우
   */
  @Transactional
  public void verifySchoolEmail(Long memberId, String schoolEmail, String code) {
    Member member = memberService.findActiveMemberForUpdate(memberId);
    String email = schoolEmailPolicy.normalize(schoolEmail);

    VerificationEntry entry = verificationStore.find(memberId)
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_VERIFICATION_CODE_NOT_FOUND));
    if (!entry.email().equalsIgnoreCase(email) || !entry.code().equals(code)) {
      throw BaseException.from(MemberErrorCode.MEMBER_VERIFICATION_CODE_MISMATCH);
    }

    memberService.releaseSchoolEmailForReuse(memberId, email);
    member.verifySchoolEmail(email);
    try {
      memberRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw BaseException.from(MemberErrorCode.MEMBER_SCHOOL_EMAIL_DUPLICATED);
    }
    verificationStore.delete(memberId);
  }

  /**
   * 온보딩 프로필을 저장하고 가입을 완료한다({@code VERIFIED → COMPLETED}).
   *
   * <p>닉네임은 회원에 세팅하고, 공개 프로필(성별·생년·학과·MBTI)은 {@code Feed}로, 선택 키워드는
   * {@link MemberKeyword} 행으로 저장한다. 키워드는 존재·활성 검증과 필수 카테고리(각 최소 1개) 검증을
   * 거친다({@link MemberKeywordService#resolveSelection}). 닉네임 유니크 충돌은 도메인 예외로 변환한다.
   * 온보딩 입력은 30일 변경 제한의 "변경"으로 치지 않는다(#95).
   *
   * @throws BaseException 학교 인증 전/이미 완료/닉네임 중복, 또는 키워드가 유효하지 않거나 필수 카테고리 누락인 경우
   */
  @Transactional
  public MemberProfileResponse completeOnboarding(Long memberId, OnboardingRequest request) {
    // 동시 탈퇴(#107)와 직렬화한다. 탈퇴가 먼저 커밋되면 비활성으로 막혀 탈퇴 회원에게 피드가 새로 생기지 않는다.
    Member member = memberService.findActiveMemberForUpdate(memberId);

    List<KeywordTag> selectedTags =
        memberKeywordService.resolveSelection(request.keywordTagIds(), Set.of());

    member.completeOnboarding(request.nickName());
    try {
      memberRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw BaseException.from(MemberErrorCode.MEMBER_NICKNAME_DUPLICATED);
    }

    memberKeywordService.saveInitialSelection(member, selectedTags);

    Feed feed = feedService.createFeed(
        member, request.gender(), request.birthYear(), request.department(), request.mbti());

    return MemberProfileResponse.of(member, feed,
        memberKeywordService.findSelections(memberId), LocalDateTime.now());
  }
}
