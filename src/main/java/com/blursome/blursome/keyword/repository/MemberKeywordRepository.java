package com.blursome.blursome.keyword.repository;

import com.blursome.blursome.keyword.domain.MemberKeyword;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberKeywordRepository extends JpaRepository<MemberKeyword, Long> {

  /** 회원이 선택한 태그 전체를 태그·카테고리까지 함께 조회한다(프로필 응답용, N+1 회피). */
  @Query("select mk from MemberKeyword mk "
      + "join fetch mk.tag t join fetch t.category c "
      + "where mk.member.id = :memberId order by c.sortOrder asc, t.id asc")
  List<MemberKeyword> findByMemberIdWithTag(@Param("memberId") Long memberId);

  /**
   * 여러 회원의 태그 선택을 태그·카테고리까지 한 번에 조회한다(탐색 후보 K 점수 계산용, N+1 회피).
   * 호출 측에서 {@code member.id}로 그룹핑한다.
   */
  @Query("select mk from MemberKeyword mk "
      + "join fetch mk.tag t join fetch t.category c "
      + "where mk.member.id in :memberIds")
  List<MemberKeyword> findByMemberIdInWithTag(@Param("memberIds") Collection<Long> memberIds);

  /** 회원의 키워드 선택을 모두 삭제한다(온보딩 재설정 등). */
  void deleteByMemberId(Long memberId);
}
