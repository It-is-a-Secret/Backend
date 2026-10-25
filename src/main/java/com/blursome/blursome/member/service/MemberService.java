package com.blursome.blursome.member.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원(Member) 도메인의 핵심 비즈니스 로직을 담당하는 서비스.
 *
 * <p>OAuth 로그인 과정에서의 회원 조회·생성, 탈퇴 회원의 30일 재가입 제한과 식별자 해제(#107), 활성 회원 조회를
 * 제공한다. 클래스 레벨에서 {@code @Transactional(readOnly = true)}를 적용하고, 쓰기가 필요한 메서드에만
 * {@code @Transactional}을 재정의한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

  private static final String PROVIDER_UNIQUE_CONSTRAINT = "uk_member_provider";
  private static final DateTimeFormatter REJOIN_AT_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final MemberRepository memberRepository;
  private final Clock clock;

  /**
   * OAuth 사용자 정보를 기준으로 기존 회원을 조회하거나, 없으면 새 회원을 생성한다.
   *
   * <p>활성 회원이면 OAuth 프로필 정보(이름·프로필 이미지)를 최신 값으로 갱신하고 마지막 활동 시각을 기록한다.
   * 가입 이력이 없으면 새 회원을 생성한다.
   *
   * <ul>
   *   <li>정지({@code SUSPENDED}) 회원은 로그인을 거부한다(#75).</li>
   *   <li>탈퇴 회원은 탈퇴 후 30일 미만이면 토큰을 발급하지 않고 {@code MEMBER_409_REJOIN_RESTRICTED}로 거부한다(#107).
   *       예전처럼 바로 재활성화하지 않는다.</li>
   *   <li>30일이 지난 탈퇴 회원은 정리 배치가 아직 돌지 않았어도 그 자리에서 식별자를 해제하고, <b>새 회원으로 가입</b>시킨다
   *       (학교 인증·온보딩부터 다시). 탈퇴 전 행은 FK 이력 보존을 위해 익명화된 채 남는다.</li>
   * </ul>
   *
   * <p>기존 회원 행은 쓰기 락으로 읽어 같은 회원의 탈퇴·학교 인증과 직렬화한다. 행이 없을 때 락 조회를 하면 동시 첫
   * 로그인끼리 갭 락으로 교착할 수 있어, id만 먼저 찾고 존재가 확인된 행만 id로 잠근다.
   *
   * @param userInfo OAuth 제공자로부터 전달받은 사용자 정보
   * @return 조회 또는 생성된 회원 엔티티
   * @throws BaseException 정지된 회원({@link MemberErrorCode#MEMBER_SUSPENDED}), 재가입 제한 기간의 탈퇴 회원
   *     ({@link MemberErrorCode#MEMBER_REJOIN_RESTRICTED})
   */
  @Transactional
  public Member findOrCreateByOAuth(OAuthUserInfo userInfo) {
    Optional<Member> found = memberRepository
        .findIdByProviderAndProviderId(userInfo.provider(), userInfo.providerId())
        .flatMap(memberRepository::findByIdForUpdate);
    if (found.isEmpty()) {
      return createMember(userInfo);
    }
    Member existing = found.get();
    if (existing.isSuspended()) {
      throw BaseException.from(MemberErrorCode.MEMBER_SUSPENDED);
    }
    if (existing.isWithdrawn()) {
      LocalDateTime now = LocalDateTime.now(clock);
      requireRejoinAllowed(existing, now);
      existing.releaseIdentifiers(now);
      memberRepository.flush();
      return createMember(userInfo);
    }
    existing.updateProfileFromOAuth(userInfo.name(), userInfo.profileImageUrl());
    existing.recordActivity();
    return existing;
  }

  /**
   * 다른 회원이 이 학교 이메일을 쓸 수 없게 막고 있는지 확인한다(#107, 인증 코드 발송 단계). 저장은 하지 않는다.
   *
   * @param memberId 이메일을 인증하려는 회원
   * @param schoolEmail 정규화된 학교 이메일
   * @throws BaseException 다른 활성·정지 회원이 사용 중({@code MEMBER_409_SCHOOL_EMAIL_DUPLICATED})이거나, 탈퇴 회원이
   *     30일 재가입 제한 기간 동안 보유 중({@code MEMBER_409_REJOIN_RESTRICTED})인 경우
   */
  public void validateSchoolEmailAvailable(Long memberId, String schoolEmail) {
    memberRepository.findBySchoolEmail(schoolEmail)
        .filter(holder -> !holder.getId().equals(memberId))
        .ifPresent(holder -> validateHolderReleasable(holder, LocalDateTime.now(clock)));
  }

  /**
   * 학교 이메일을 저장하기 직전에 다른 보유자를 다시 확인하고, 재가입 제한이 끝난 탈퇴 회원이 쥐고 있으면 그 자리에서
   * 해제한다(#107, 인증 코드 검증 단계). 보유 회원 행을 쓰기 락으로 잡아 동시 탈퇴·로그인과 직렬화한다. 호출 측 쓰기
   * 트랜잭션 안에서 사용해야 한다.
   *
   * @throws BaseException {@link #validateSchoolEmailAvailable}과 같은 조건
   */
  @Transactional
  public void releaseSchoolEmailForReuse(Long memberId, String schoolEmail) {
    Optional<Member> holder = memberRepository.findIdBySchoolEmail(schoolEmail)
        .filter(holderId -> !holderId.equals(memberId))
        .flatMap(memberRepository::findByIdForUpdate);
    if (holder.isEmpty()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    validateHolderReleasable(holder.get(), now);
    holder.get().releaseIdentifiers(now);
    memberRepository.flush();
  }

  /**
   * 재가입 제한이 끝났는데 아직 식별자를 쥐고 있는 탈퇴 회원 한 명을 해제한다(#107 정리 배치). 그사이 다른 경로가 먼저
   * 해제했거나 상태가 바뀌었으면 건너뛴다.
   *
   * @return 실제로 해제했으면 {@code true}
   */
  @Transactional
  public boolean releaseExpiredWithdrawnMember(Long memberId) {
    return memberRepository.findByIdForUpdate(memberId)
        .filter(Member::isWithdrawn)
        .filter(member -> !member.isIdentifiersReleased())
        .filter(member -> !member.isRejoinRestricted(LocalDateTime.now(clock)))
        .map(member -> {
          member.releaseIdentifiers(LocalDateTime.now(clock));
          return true;
        })
        .orElse(false);
  }

  /** 다른 회원이 보유한 학교 이메일이 지금 재사용 가능한지 판정한다. 해제 대상이면 통과, 아니면 예외. */
  private void validateHolderReleasable(Member holder, LocalDateTime now) {
    if (!holder.isWithdrawn()) {
      throw BaseException.from(MemberErrorCode.MEMBER_SCHOOL_EMAIL_DUPLICATED);
    }
    requireRejoinAllowed(holder, now);
  }

  private void requireRejoinAllowed(Member withdrawn, LocalDateTime now) {
    if (withdrawn.isRejoinRestricted(now)) {
      throw BaseException.from(MemberErrorCode.MEMBER_REJOIN_RESTRICTED,
          "탈퇴 후 30일 동안은 같은 계정이나 학교 이메일로 다시 가입할 수 없습니다. %s부터 가입할 수 있습니다."
              .formatted(withdrawn.rejoinAvailableAt().format(REJOIN_AT_FORMAT)));
    }
  }

  /**
   * 신규 OAuth 회원을 생성하여 저장한다.
   *
   * <p>동시 로그인 요청(예: 이중 클릭) 시 {@code uk_member_provider} 제약 충돌이 발생할 수 있으므로
   * {@code saveAndFlush}로 즉시 검증한다. provider 유니크 충돌만 명시적 도메인 예외
   * ({@link MemberErrorCode#MEMBER_OAUTH_CONFLICT})로 변환하고, 그 외 무결성 위반은 실제 원인을
   * 로깅한 뒤 그대로 전파해 충돌로 오인되지 않게 한다.
   *
   * @param userInfo OAuth 제공자로부터 전달받은 사용자 정보
   * @return 새로 저장된 회원 엔티티
   * @throws BaseException provider/providerId 중복으로 저장에 실패한 경우
   */
  private Member createMember(OAuthUserInfo userInfo) {
    try {
      return memberRepository.saveAndFlush(
          Member.createOAuthMember(
              userInfo.provider(),
              userInfo.providerId(),
              userInfo.name(),
              userInfo.email(),
              userInfo.profileImageUrl()
          )
      );
    } catch (DataIntegrityViolationException e) {
      if (isProviderUniqueViolation(e)) {
        throw BaseException.from(MemberErrorCode.MEMBER_OAUTH_CONFLICT);
      }
      log.error("회원 생성 중 데이터 무결성 위반: {}", e.getMostSpecificCause().getMessage());
      throw e;
    }
  }

  /** 무결성 위반이 {@code uk_member_provider}(동일 소셜 계정 중복 가입) 때문인지 판별한다. */
  private boolean isProviderUniqueViolation(DataIntegrityViolationException e) {
    if (e.getCause() instanceof ConstraintViolationException cve
        && cve.getConstraintName() != null) {
      return cve.getConstraintName().toLowerCase().contains(PROVIDER_UNIQUE_CONSTRAINT);
    }
    return false;
  }

  /**
   * 식별자로 회원을 조회하되, 활성 상태인 회원만 반환한다.
   *
   * @param id 조회할 회원의 식별자
   * @return 활성 상태의 회원 엔티티
   * @throws BaseException 회원이 존재하지 않거나({@link MemberErrorCode#MEMBER_NOT_FOUND}),
   *     비활성 상태인 경우({@link MemberErrorCode#MEMBER_INACTIVE})
   */
  public Member findActiveMember(Long id) {
    return requireActive(memberRepository.findById(id));
  }

  /**
   * {@link #findActiveMember}와 같지만 회원 행에 쓰기 락을 건다(#95 프로필 수정, #107 탈퇴·학교 인증 직렬화). 호출 측
   * 쓰기 트랜잭션 안에서 사용해야 한다.
   */
  public Member findActiveMemberForUpdate(Long id) {
    return requireActive(memberRepository.findByIdForUpdate(id));
  }

  /** 본인을 제외하고 닉네임이 이미 사용 중인지 확인한다(#95). */
  public boolean isNickNameTaken(String nickName, Long excludeMemberId) {
    return memberRepository.existsByNickNameAndIdNot(nickName, excludeMemberId);
  }

  private Member requireActive(Optional<Member> found) {
    Member member = found
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_NOT_FOUND));
    if (!member.isActive()) {
      throw BaseException.from(MemberErrorCode.MEMBER_INACTIVE);
    }
    return member;
  }
}
