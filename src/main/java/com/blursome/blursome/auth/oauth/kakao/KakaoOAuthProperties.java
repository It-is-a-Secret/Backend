package com.blursome.blursome.auth.oauth.kakao;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.oauth.kakao")
public record KakaoOAuthProperties(
    String clientId,
    String clientSecret,
    List<String> redirectUris,
    String authorizeUri,
    String tokenUri,
    String userInfoUri
) {

  /**
   * 요청된 redirect_uri를 검증해 사용할 값을 반환한다.
   *
   * <p>값이 없으면 화이트리스트의 첫 번째(기본)를 사용하고, 값이 있으면 화이트리스트에
   * 포함된 경우에만 그대로 사용한다. 미등록 값은 호출 측에서 거부하도록 {@code null}을 반환한다.
   *
   * @param requested 클라이언트가 보낸 redirect_uri (null/blank 허용)
   * @return 사용할 redirect_uri, 허용되지 않은 값이면 {@code null}
   */
  public String resolveRedirectUri(String requested) {
    if (requested == null || requested.isBlank()) {
      return defaultRedirectUri();
    }
    return redirectUris != null && redirectUris.contains(requested) ? requested : null;
  }

  /** 화이트리스트의 첫 번째 값을 기본 redirect_uri로 반환한다. */
  public String defaultRedirectUri() {
    return redirectUris == null || redirectUris.isEmpty() ? null : redirectUris.get(0);
  }
}
