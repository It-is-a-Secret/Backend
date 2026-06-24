package com.blursome.blursome.member.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원(Member) 도메인의 핵심 비즈니스 로직을 담당하는 서비스.
 *
 * <p>OAuth 로그인 과정에서의 회원 조회·생성·재활성화와 활성 회원 조회를 제공한다.
 * 클래스 레벨에서 {@code @Transactional(readOnly = true)}를 적용하고, 쓰기가 필요한 메서드에만 {@code @Transactional}을
 * 재정의한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

  private static final String PROVIDER_UNIQUE_CONSTRAINT = "uk_member_provider";

  private final MemberRepository memberRepository;

  /**
   * OAuth 사용자 정보를 기준으로 기존 회원을 조회하거나, 없으면 새 회원을 생성한다.
   *
   * <p>이미 가입된 회원이면 탈퇴 상태인 경우 재활성화한 뒤 OAuth 프로필 정보(이름·프로필 이미지)를
   * 최신 값으로 갱신하고, 로그인은 활동이므로 마지막 활동 시각을 갱신한다. 가입 이력이 없으면 새 회원을
   * 생성한다(생성 시 {@code lastActiveAt}이 초기화됨).
   *
   * @param userInfo OAuth 제공자로부터 전달받은 사용자 정보
   * @return 조회 또는 생성된 회원 엔티티
   */
  @Transactional
  public Member findOrCreateByOAuth(OAuthUserInfo userInfo) {
    return memberRepository.findByProviderAndProviderId(userInfo.provider(), userInfo.providerId())
        .map(existing -> {
          if (existing.isWithdrawn()) {
            existing.reactivate();
          }
          existing.updateProfileFromOAuth(userInfo.name(), userInfo.profileImageUrl());
          existing.recordActivity();
          return existing;
        })
        .orElseGet(() -> createMember(userInfo));
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
    Member member = memberRepository.findById(id)
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_NOT_FOUND));
    if (!member.isActive()) {
      throw BaseException.from(MemberErrorCode.MEMBER_INACTIVE);
    }
    return member;
  }
}
