package com.blursome.blursome.member.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

  private final MemberRepository memberRepository;

  @Transactional
  public Member findOrCreateByOAuth(OAuthUserInfo userInfo) {
    return memberRepository.findByProviderAndProviderId(userInfo.provider(), userInfo.providerId())
        .map(existing -> {
          if (existing.isWithdrawn()) {
            existing.reactivate();
          }
          existing.updateProfileFromOAuth(userInfo.name(), userInfo.profileImageUrl());
          return existing;
        })
        .orElseGet(() -> createMember(userInfo));
  }

  // 동시 로그인 요청(예: 이중 클릭) 시 uk_member_provider 충돌이 발생할 수 있으므로
  // saveAndFlush로 즉시 검증하고, DataIntegrityViolationException은 명시적인 도메인 예외로 변환한다.
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
      throw BaseException.from(MemberErrorCode.MEMBER_OAUTH_CONFLICT);
    }
  }

  public Member findActiveMember(Long id) {
    Member member = memberRepository.findById(id)
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_NOT_FOUND));
    if (!member.isActive()) {
      throw BaseException.from(MemberErrorCode.MEMBER_INACTIVE);
    }
    return member;
  }
}
