package com.blursome.blursome.member.service;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.InterestCategory;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.dto.request.OnboardingRequest;
import com.blursome.blursome.member.dto.response.MemberProfileResponse;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.InterestCategoryRepository;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.member.verification.EmailVerificationSender;
import com.blursome.blursome.member.verification.SchoolEmailPolicy;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore.VerificationEntry;
import com.blursome.blursome.member.verification.VerificationCodeGenerator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 온보딩 플로우(학교 이메일 인증 → 프로필 작성)를 조율하는 Facade 서비스.
 *
 * <p>플로우는 {@code 인증 코드 발송 → 코드 검증(학교 인증 완료) → 온보딩 작성(프로필·관심사 저장)}으로
 * 진행되며, 가입 단계 전이의 불변식은 {@link Member} 도메인 메서드가, 유니크 충돌 변환은 본 서비스가
 * 담당한다. 설계는 {@code docs/member/MEMBER_ONBOARDING.md} 참조.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberOnboardingService {

  private final MemberService memberService;
  private final MemberRepository memberRepository;
  private final InterestCategoryRepository interestCategoryRepository;
  private final SchoolEmailPolicy schoolEmailPolicy;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final SchoolEmailVerificationStore verificationStore;
  private final EmailVerificationSender emailVerificationSender;
  private final FeedService feedService;

  /**
   * 학교 이메일로 6자리 인증 코드를 발송한다.
   *
   * <p>도메인 정책(@gs.anyang.ac.kr) 검증 후 코드를 생성해 Redis에 TTL과 함께 저장하고 발송한다.
   * 이미 학교 인증을 마친 회원이면 재발송하지 않는다.
   *
   * @throws BaseException 회원이 비활성이거나, 도메인이 부적합하거나, 이미 인증을 마친 경우
   */
  public void sendSchoolEmailVerificationCode(Long memberId, String schoolEmail) {
    Member member = memberService.findActiveMember(memberId);
    if (member.isSchoolVerified()) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_VERIFIED);
    }
    schoolEmailPolicy.validate(schoolEmail);

    String code = verificationCodeGenerator.generate();
    verificationStore.save(memberId, schoolEmail, code, SchoolEmailVerificationStore.CODE_TTL);
    emailVerificationSender.send(schoolEmail, code);
  }

  /**
   * 인증 코드를 검증하고 학교 인증을 완료한다({@code UNVERIFIED → VERIFIED}).
   *
   * <p>저장된 코드·대상 이메일이 요청과 일치하면 {@link Member#verifySchoolEmail(String)}로 단계를
   * 전진시키고 코드를 폐기한다. 학교 이메일 유니크 충돌은 도메인 예외로 변환한다.
   *
   * @throws BaseException 코드 부재/만료, 이메일·코드 불일치, 단계 위반, 학교 이메일 중복인 경우
   */
  @Transactional
  public void verifySchoolEmail(Long memberId, String schoolEmail, String code) {
    Member member = memberService.findActiveMember(memberId);

    VerificationEntry entry = verificationStore.find(memberId)
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_VERIFICATION_CODE_NOT_FOUND));
    if (!entry.email().equalsIgnoreCase(schoolEmail) || !entry.code().equals(code)) {
      throw BaseException.from(MemberErrorCode.MEMBER_VERIFICATION_CODE_MISMATCH);
    }

    member.verifySchoolEmail(schoolEmail);
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
   * <p>닉네임은 회원에 세팅하고, 공개 프로필(성별·생년·학과·MBTI)은 {@code Feed}로, 관심사는 별도 행으로
   * 저장한다. 닉네임 유니크 충돌은 도메인 예외로 변환한다.
   *
   * @throws BaseException 학교 인증 전이거나, 이미 온보딩을 마쳤거나, 닉네임이 중복인 경우
   */
  @Transactional
  public MemberProfileResponse completeOnboarding(Long memberId, OnboardingRequest request) {
    Member member = memberService.findActiveMember(memberId);

    member.completeOnboarding(request.nickName());
    try {
      memberRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw BaseException.from(MemberErrorCode.MEMBER_NICKNAME_DUPLICATED);
    }

    List<InterestCategory> interests = request.interests().stream()
        .distinct()
        .map(type -> InterestCategory.of(member, type, null))
        .toList();
    interestCategoryRepository.saveAll(interests);

    Feed feed = feedService.createFeed(
        member, request.gender(), request.birthYear(), request.department(), request.mbti());

    return MemberProfileResponse.of(member, feed, interests);
  }

  /** 내 프로필을 공개 프로필(Feed)·관심사와 함께 조회한다. 온보딩 미완료 회원은 Feed가 없어 해당 필드가 null. */
  public MemberProfileResponse getMyProfile(Long memberId) {
    Member member = memberService.findActiveMember(memberId);
    Feed feed = feedService.findByMemberId(memberId).orElse(null);
    return MemberProfileResponse.of(
        member, feed, interestCategoryRepository.findByMemberId(memberId));
  }
}
