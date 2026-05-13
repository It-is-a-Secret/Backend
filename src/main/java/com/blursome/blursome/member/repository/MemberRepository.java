package com.blursome.blursome.member.repository;

import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

  /** OAuth 공급자와 공급자 고유 ID로 회원을 조회한다. */
  Optional<Member> findByProviderAndProviderId(OAuthProvider provider, String providerId);
}
