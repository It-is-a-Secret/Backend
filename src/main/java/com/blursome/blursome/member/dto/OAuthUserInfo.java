package com.blursome.blursome.member.dto;

import com.blursome.blursome.member.domain.OAuthProvider;

public record OAuthUserInfo(
    OAuthProvider provider,
    String providerId,
    String email,
    String nickname,
    String profileImageUrl
) {
}
