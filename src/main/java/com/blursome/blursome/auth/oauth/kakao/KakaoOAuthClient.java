package com.blursome.blursome.auth.oauth.kakao;

import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.auth.oauth.OAuthClient;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class KakaoOAuthClient implements OAuthClient {

  private static final String GRANT_TYPE = "authorization_code";

  private final KakaoOAuthProperties properties;
  private final RestClient restClient;

  public KakaoOAuthClient(KakaoOAuthProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    this.restClient = builder.build();
  }

  @Override
  public OAuthProvider provider() {
    return OAuthProvider.KAKAO;
  }

  @Override
  public OAuthUserInfo fetchUserInfo(String authorizationCode) {
    KakaoTokenResponse token = requestAccessToken(authorizationCode);
    KakaoUserInfoResponse userInfo = requestUserInfo(token.accessToken());
    return new OAuthUserInfo(
        OAuthProvider.KAKAO,
        String.valueOf(userInfo.id()),
        userInfo.email(),
        resolveName(userInfo),
        userInfo.profileImageUrl()
    );
  }

  private KakaoTokenResponse requestAccessToken(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", GRANT_TYPE);
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    form.add("redirect_uri", properties.redirectUri());
    form.add("code", code);

    try {
      KakaoTokenResponse response = restClient.post()
          .uri(properties.tokenUri())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .accept(MediaType.APPLICATION_JSON)
          .body(form)
          .retrieve()
          .body(KakaoTokenResponse.class);
      if (response == null || response.accessToken() == null) {
        throw BaseException.from(AuthErrorCode.OAUTH_TOKEN_EXCHANGE_FAILED);
      }
      return response;
    } catch (RestClientException e) {
      log.warn("카카오 토큰 교환 실패: {}", e.getMessage());
      throw BaseException.from(AuthErrorCode.OAUTH_TOKEN_EXCHANGE_FAILED);
    }
  }

  private KakaoUserInfoResponse requestUserInfo(String kakaoAccessToken) {
    try {
      KakaoUserInfoResponse response = restClient.get()
          .uri(properties.userInfoUri())
          .header("Authorization", "Bearer " + kakaoAccessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(KakaoUserInfoResponse.class);
      if (response == null || response.id() == null) {
        throw BaseException.from(AuthErrorCode.OAUTH_USER_INFO_FETCH_FAILED);
      }
      return response;
    } catch (RestClientException e) {
      log.warn("카카오 사용자 정보 조회 실패: {}", e.getMessage());
      throw BaseException.from(AuthErrorCode.OAUTH_USER_INFO_FETCH_FAILED);
    }
  }

  private String resolveName(KakaoUserInfoResponse userInfo) {
    String nickname = userInfo.nickname();
    if (nickname != null && !nickname.isBlank()) {
      return nickname;
    }
    return "kakao_" + userInfo.id();
  }
}
