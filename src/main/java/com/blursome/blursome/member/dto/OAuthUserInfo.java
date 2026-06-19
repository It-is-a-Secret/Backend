package com.blursome.blursome.member.dto;

import com.blursome.blursome.member.domain.OAuthProvider;

/**
 * OAuth 공급자에서 조회한 사용자 정보.
 *
 * <p>{@code name}은 소셜 로그인이 제공하는 이름이며, 온보딩에서 사용자가 직접 입력하는
 * 닉네임({@code Member.nickName})과는 별개다.
 */
public record OAuthUserInfo(
    OAuthProvider provider,
    String providerId,
    String email,
    String name,
    String profileImageUrl
) {
}
