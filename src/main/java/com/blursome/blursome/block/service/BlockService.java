package com.blursome.blursome.block.service;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.block.exception.BlockErrorCode;
import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 차단(block) 서비스. 차단 등록·해제를 제공한다. 노출 차단(탐색·채팅 비노출)은 조회 쿼리에서 양방향으로 적용한다.
 *
 * <p>등록·해제는 모두 멱등이다(중복 차단/없는 차단 해제는 오류 없이 무시). 설계는 마스터 §5.5.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

  private final BlockRepository blockRepository;
  private final MemberRepository memberRepository;

  /**
   * {@code blockerId}가 {@code targetMemberId}를 차단한다. 자기 차단은 거부하고, 이미 차단한 상태면 멱등하게 무시한다.
   *
   * @throws BaseException 자기 자신을 차단하거나({@link BlockErrorCode#BLOCK_SELF_NOT_ALLOWED}),
   *     대상 회원이 없는 경우({@link MemberErrorCode#MEMBER_NOT_FOUND})
   */
  @Transactional
  public void block(Long blockerId, Long targetMemberId) {
    if (blockerId.equals(targetMemberId)) {
      throw BaseException.from(BlockErrorCode.BLOCK_SELF_NOT_ALLOWED);
    }
    if (!memberRepository.existsById(targetMemberId)) {
      throw BaseException.from(MemberErrorCode.MEMBER_NOT_FOUND);
    }
    if (blockRepository.existsByBlockerIdAndBlockedId(blockerId, targetMemberId)) {
      return;
    }
    try {
      blockRepository.save(Block.of(
          memberRepository.getReferenceById(blockerId),
          memberRepository.getReferenceById(targetMemberId)));
    } catch (DataIntegrityViolationException e) {
      // 동시 요청(이중 클릭)으로 uk_block_pair 충돌 시 이미 차단된 것이므로 멱등 처리.
    }
  }

  /** 차단을 해제한다. 차단 상태가 아니어도 멱등하게 무시한다. */
  @Transactional
  public void unblock(Long blockerId, Long targetMemberId) {
    blockRepository.deleteByBlockerIdAndBlockedId(blockerId, targetMemberId);
  }
}
