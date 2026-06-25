package com.blursome.blursome.block.repository;

import com.blursome.blursome.block.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockRepository extends JpaRepository<Block, Long> {

  boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /** 차단 해제. 행이 없으면 0건 삭제(멱등). */
  long deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
}
