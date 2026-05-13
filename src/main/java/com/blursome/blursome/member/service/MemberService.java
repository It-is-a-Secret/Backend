package com.blursome.blursome.member.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
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
          existing.updateProfileFromOAuth(userInfo.nickname(), userInfo.profileImageUrl());
          return existing;
        })
        .orElseGet(() -> memberRepository.save(
            Member.createOAuthMember(
                userInfo.provider(),
                userInfo.providerId(),
                userInfo.email(),
                userInfo.nickname(),
                userInfo.profileImageUrl()
            )
        ));
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
