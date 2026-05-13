package com.blursome.blursome.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthClientResolverTest {

  @Test
  @DisplayName("등록된 Provider는 해당 OAuthClient를 반환한다")
  void resolve_whenProviderRegistered_thenReturnsClient() {
    // given
    OAuthClient kakao = new StubKakaoClient();
    OAuthClientResolver resolver = new OAuthClientResolver(List.of(kakao));

    // when
    OAuthClient resolved = resolver.resolve(OAuthProvider.KAKAO);

    // then
    assertThat(resolved).isSameAs(kakao);
  }

  @Test
  @DisplayName("등록되지 않은 Provider 요청 시 OAUTH_PROVIDER_NOT_SUPPORTED 예외가 발생한다")
  void resolve_whenProviderUnknown_thenThrows() {
    // given
    OAuthClientResolver resolver = new OAuthClientResolver(List.of());

    // when & then
    assertThatThrownBy(() -> resolver.resolve(OAuthProvider.KAKAO))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            AuthErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED.getCode());
  }

  private static class StubKakaoClient implements OAuthClient {
    @Override
    public OAuthProvider provider() {
      return OAuthProvider.KAKAO;
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String authorizationCode) {
      return new OAuthUserInfo(OAuthProvider.KAKAO, "1", "e@e.com", "blur", null);
    }
  }
}
