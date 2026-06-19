package com.blursome.blursome.auth.oauth.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserInfoResponse(
    Long id,
    @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

  /** 카카오 계정의 이메일을 반환한다. */
  public String email() {
    return kakaoAccount != null ? kakaoAccount.email() : null;
  }

  /** 카카오 프로필 닉네임을 반환한다. */
  public String nickname() {
    return kakaoAccount != null && kakaoAccount.profile() != null
        ? kakaoAccount.profile().nickname()
        : null;
  }

  /** 카카오 프로필 이미지 URL을 반환한다. */
  public String profileImageUrl() {
    return kakaoAccount != null && kakaoAccount.profile() != null
        ? kakaoAccount.profile().profileImageUrl()
        : null;
  }

  public record KakaoAccount(
      String email,
      Profile profile
  ) {
  }

  public record Profile(
      String nickname,
      @JsonProperty("profile_image_url") String profileImageUrl
  ) {
  }
}
