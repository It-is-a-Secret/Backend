package com.blursome.blursome.member.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.MemberAccessValidator;
import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 회원이 남은 Access Token·STOMP 연결로 서비스를 계속 쓰지 못하게 막는 검증 구현(#107).
 *
 * <p>요청마다 호출되므로 회원 행 전체가 아니라 존재 여부만 PK로 확인한다. 탈퇴({@code WITHDRAWN})만 막고, 회원 행이 없는
 * 토큰은 통과시켜 기존처럼 각 기능이 {@code MEMBER_404_NOT_FOUND} 등으로 처리하게 둔다. 신고 정지({@code SUSPENDED}) 회원은
 * 기존 정책대로 로그인·채팅·탐색 게이트가 막으며 이 검증의 대상이 아니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberAccessService implements MemberAccessValidator {

  private final MemberRepository memberRepository;

  /**
   * @throws BaseException 탈퇴한 회원인 경우({@code MEMBER_403_INACTIVE})
   */
  @Override
  public void validateAccess(Long memberId) {
    if (memberId != null
        && memberRepository.existsByIdAndActivityStatus(memberId, ActivityStatus.WITHDRAWN)) {
      throw BaseException.from(MemberErrorCode.MEMBER_INACTIVE);
    }
  }
}
