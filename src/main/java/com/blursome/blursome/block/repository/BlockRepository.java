package com.blursome.blursome.block.repository;

import com.blursome.blursome.block.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockRepository extends JpaRepository<Block, Long> {

  boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /** 차단 해제. 행이 없으면 0건 삭제(멱등). */
  long deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /**
   * 두 회원 사이에 어느 방향이든 차단이 존재하는지 조회한다(양방향, 이슈 #77). 신규 채팅 개설 방어
   * ({@code openRoom})와 차단 해제 시 "반대 방향 차단이 아직 남아 있는지" 판정(양방향 모두 풀려야 방 복구)에
   * 쓴다. 자기 자신 인자는 호출 측이 선검증한다.
   */
  @Query("select count(b) > 0 from Block b where "
      + "(b.blocker.id = :memberAId and b.blocked.id = :memberBId) "
      + "or (b.blocker.id = :memberBId and b.blocked.id = :memberAId)")
  boolean existsBlockBetween(@Param("memberAId") Long memberAId,
      @Param("memberBId") Long memberBId);
}
