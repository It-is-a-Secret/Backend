package com.blursome.blursome.block.service;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.block.exception.BlockErrorCode;
import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.service.ChatRoomService;
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
 *
 * <p>진행 중 채팅방 연동(이슈 #77): 차단 시 두 회원 사이 ACTIVE 방을 동결({@code BLOCKED})하고, 차단 해제
 * 시 <b>양방향 차단이 모두 풀렸을 때만</b> ACTIVE로 복구한다. 채팅 상태 전이는 chat 도메인이 소유하므로
 * {@link ChatRoomService}에 위임한다(Service → Service). 비노출·송신 차단은 chat 조회/쓰기 경로가 차단
 * 관계·방 상태로 직접 판별한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

  private final BlockRepository blockRepository;
  private final MemberRepository memberRepository;
  private final ChatRoomService chatRoomService;

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
    if (!blockRepository.existsByBlockerIdAndBlockedId(blockerId, targetMemberId)) {
      try {
        blockRepository.save(Block.of(
            memberRepository.getReferenceById(blockerId),
            memberRepository.getReferenceById(targetMemberId)));
      } catch (DataIntegrityViolationException e) {
        // 동시 요청(이중 클릭)으로 uk_block_pair 충돌 시 이미 차단된 것이므로 멱등 처리.
      }
    }
    // 차단이 성립한 상태(신규 저장/이미 차단)에서 진행 중 방을 동결한다(이슈 #77). markBlocked는 ACTIVE일 때만
    // 전이하므로 이미 동결됐거나 동결할 방이 없으면 멱등 no-op이다.
    chatRoomService.freezeRoomOnBlock(blockerId, targetMemberId);
  }

  /**
   * 차단을 해제한다. 차단 상태가 아니어도 멱등하게 무시한다. 해제 후 두 회원 사이에 <b>반대 방향 차단이 남아
   * 있지 않을 때만</b> 동결된 방을 ACTIVE로 복구한다(이슈 #77) — 한쪽만 풀어도 다른 쪽 차단이 남으면 방은 계속
   * 동결 상태로 둔다.
   */
  @Transactional
  public void unblock(Long blockerId, Long targetMemberId) {
    blockRepository.deleteByBlockerIdAndBlockedId(blockerId, targetMemberId);
    if (!blockRepository.existsBlockBetween(blockerId, targetMemberId)) {
      chatRoomService.restoreRoomOnUnblock(blockerId, targetMemberId);
    }
  }
}
