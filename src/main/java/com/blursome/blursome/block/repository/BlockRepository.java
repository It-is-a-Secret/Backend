package com.blursome.blursome.block.repository;

import com.blursome.blursome.block.domain.Block;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockRepository extends JpaRepository<Block, Long> {

  boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /**
   * {@code blockerId}가 차단한 목록을 최근 차단 순으로 조회한다(#113). 표시할 닉네임을 위해 차단 대상 회원을 fetch join으로
   * 함께 읽어 N+1을 막는다. {@code blocker_id} 필터는 {@code uk_block_pair}의 선두 컬럼을 탄다.
   *
   * <p>탈퇴 회원은 제외한다. 탈퇴 회원은 다시 활성화되지 않고(재가입은 새 회원 행), 차단 행은 이력으로만 남으므로 관리
   * 대상이 아니다.
   */
  @Query("select b from Block b join fetch b.blocked m "
      + "where b.blocker.id = :blockerId "
      + "and m.activityStatus <> com.blursome.blursome.member.domain.ActivityStatus.WITHDRAWN "
      + "order by b.createdAt desc, b.id desc")
  List<Block> findBlockedMembers(@Param("blockerId") Long blockerId);

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
