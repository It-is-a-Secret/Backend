package com.blursome.blursome.auth.oauth;

import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;

public interface OAuthClient {

  OAuthProvider provider();

  OAuthUserInfo fetchUserInfo(String authorizationCode);
}
