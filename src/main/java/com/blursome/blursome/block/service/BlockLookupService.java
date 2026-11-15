package com.blursome.blursome.block.service;

import com.blursome.blursome.block.repository.BlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 도메인이 차단 관계를 읽을 때 쓰는 조회 전용 서비스(#129).
 *
 * <p>{@link BlockService}는 차단 시 채팅방을 동결하려고 {@code ChatRoomService}에 의존하고, 채팅은 사진 공개 때문에 피드
 * 서비스에 의존한다. 피드 서비스가 {@link BlockService}를 쓰면 순환 의존이 되므로, 차단 조회만 {@link BlockRepository} 하나에
 * 의존하는 이 서비스로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockLookupService {

  private final BlockRepository blockRepository;

  /** 두 회원 사이에 어느 방향이든 차단이 있는지 확인한다. */
  public boolean isBlockedBetween(Long memberAId, Long memberBId) {
    return blockRepository.existsBlockBetween(memberAId, memberBId);
  }
}
