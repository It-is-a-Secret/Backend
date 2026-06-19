package com.blursome.blursome.member.repository;

import com.blursome.blursome.member.domain.InterestCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestCategoryRepository extends JpaRepository<InterestCategory, Long> {

  /** 회원이 선택한 관심사 전체를 조회한다. */
  List<InterestCategory> findByMemberId(Long memberId);

  /** 회원의 관심사를 모두 삭제한다(온보딩 재설정 등). */
  void deleteByMemberId(Long memberId);
}
