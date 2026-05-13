package com.blursome.blursome.auth.oauth.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.oauth.kakao")
public record KakaoOAuthProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String tokenUri,
    String userInfoUri
) {
}
