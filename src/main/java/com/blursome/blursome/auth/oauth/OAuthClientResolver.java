package com.blursome.blursome.auth.oauth;

import com.blursome.blursome.auth.exception.AuthErrorCode;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class OAuthClientResolver {

  private final Map<OAuthProvider, OAuthClient> clients;

  public OAuthClientResolver(List<OAuthClient> clients) {
    this.clients = clients.stream()
        .collect(Collectors.toUnmodifiableMap(OAuthClient::provider, Function.identity()));
  }

  /** 주어진 공급자에 해당하는 {@link OAuthClient}를 반환한다. */
  public OAuthClient resolve(OAuthProvider provider) {
    OAuthClient client = clients.get(provider);
    if (client == null) {
      throw BaseException.from(AuthErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED);
    }
    return client;
  }
}
