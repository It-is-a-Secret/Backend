package com.blursome.blursome.global.security;

import com.blursome.blursome.member.domain.MemberRole;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class JwtAuthentication extends AbstractAuthenticationToken {

  private final Long memberId;

  private JwtAuthentication(Long memberId, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.memberId = memberId;
    setAuthenticated(true);
  }

  public static JwtAuthentication of(Long memberId, MemberRole role) {
    return new JwtAuthentication(
        memberId,
        List.of(new SimpleGrantedAuthority(role.getAuthority()))
    );
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Long getPrincipal() {
    return memberId;
  }
}
