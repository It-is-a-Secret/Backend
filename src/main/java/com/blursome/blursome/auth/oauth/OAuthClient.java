package com.blursome.blursome.auth.oauth;

import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;

/**
 * OAuth 공급자별 인증 클라이언트 계약.
 *
 * <p>구현체는 각 공급자(카카오·구글·네이버 등)의 인가 코드 교환 및
 * 사용자 정보 조회를 담당한다. {@link OAuthClientResolver}가 {@link #provider()}
 * 값을 키로 구현체를 라우팅하므로, 신규 공급자 추가 시 본 인터페이스 구현체와
 * {@link OAuthProvider} enum 값만 추가하면 된다.
 */
public interface OAuthClient {

  /** 이 클라이언트가 담당하는 OAuth 공급자를 반환한다. */
  OAuthProvider provider();

  /**
   * 인가 코드로 액세스 토큰을 교환하고 공급자로부터 사용자 정보를 조회한다.
   *
   * @param authorizationCode 클라이언트가 공급자 인가 화면에서 발급받은 1회용 코드
   * @return 공급자 식별 정보(provider, providerId)와 프로필을 담은 사용자 정보
   */
  OAuthUserInfo fetchUserInfo(String authorizationCode);
}
