package com.blursome.blursome.member.repository;

import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

  /** OAuth 공급자와 공급자 고유 ID로 회원을 조회한다. */
  Optional<Member> findByProviderAndProviderId(OAuthProvider provider, String providerId);

  /**
   * 학교 이메일을 보유한 회원을 조회한다(#107). 탈퇴 회원도 30일 동안은 보유하므로 활동 상태와 무관하게 찾는다.
   * 비교는 DB collation 기준(대소문자 무시)이며, 호출 측은 정규화한 값을 넘긴다.
   */
  Optional<Member> findBySchoolEmail(String schoolEmail);

  /**
   * OAuth 식별자로 회원 id만 조회한다(#107). 엔티티를 먼저 적재한 뒤 같은 행을 {@link #findByIdForUpdate}로 다시 읽으면
   * 영속성 컨텍스트가 이미 가진 이전 상태를 돌려주므로, 잠그기 전에는 id만 읽는다.
   */
  @Query("select m.id from Member m where m.provider = :provider and m.providerId = :providerId")
  Optional<Long> findIdByProviderAndProviderId(
      @Param("provider") OAuthProvider provider, @Param("providerId") String providerId);

  /** 학교 이메일 보유 회원의 id만 조회한다(#107, 잠금 전 조회 — {@link #findIdByProviderAndProviderId}와 같은 이유). */
  @Query("select m.id from Member m where m.schoolEmail = :schoolEmail")
  Optional<Long> findIdBySchoolEmail(@Param("schoolEmail") String schoolEmail);

  /** 본인을 제외하고 같은 닉네임을 쓰는 회원이 있는지 확인한다(#95, 비교는 DB collation 기준). */
  boolean existsByNickNameAndIdNot(String nickName, Long id);

  /** 회원이 주어진 활동 상태인지 확인한다(#107 탈퇴 회원의 기존 토큰 차단용). 회원 행이 없으면 {@code false}. */
  boolean existsByIdAndActivityStatus(Long id, ActivityStatus activityStatus);

  /**
   * 회원을 쓰기 락으로 조회한다. 프로필 동시 수정(#95), 탈퇴·학교 인증·로그인·신고 정지(#107)처럼 같은 회원 행을
   * 고치는 요청을 직렬화한다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.id = :id")
  Optional<Member> findByIdForUpdate(@Param("id") Long id);

  /**
   * 재가입 제한(30일)이 끝났는데 아직 식별자를 해제하지 않은 탈퇴 회원을 오래된 순으로 조회한다(#107 정리 배치).
   *
   * @param threshold {@code now - 30일}. {@code withdrawnAt}이 이 시각 이하이면 대상이다.
   */
  @Query("""
      select m from Member m
      where m.activityStatus = com.blursome.blursome.member.domain.ActivityStatus.WITHDRAWN
        and m.identifiersReleasedAt is null
        and m.withdrawnAt <= :threshold
      order by m.withdrawnAt asc, m.id asc
      """)
  List<Member> findReleasableWithdrawnMembers(
      @Param("threshold") LocalDateTime threshold, Limit limit);
}
