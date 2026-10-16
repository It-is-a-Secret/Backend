package com.blursome.blursome.member.repository;

import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

  /** OAuth 공급자와 공급자 고유 ID로 회원을 조회한다. */
  Optional<Member> findByProviderAndProviderId(OAuthProvider provider, String providerId);

  /** 본인을 제외하고 같은 닉네임을 쓰는 회원이 있는지 확인한다(#95, 비교는 DB collation 기준). */
  boolean existsByNickNameAndIdNot(String nickName, Long id);

  /**
   * 회원을 쓰기 락으로 조회한다(#95). 프로필 동시 수정 요청이 둘 다 30일 제한 검증을 통과하지 못하도록
   * 같은 회원의 수정을 직렬화한다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.id = :id")
  Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
