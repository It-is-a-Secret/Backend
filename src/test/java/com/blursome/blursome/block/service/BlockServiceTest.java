package com.blursome.blursome.block.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.block.domain.Block;
import com.blursome.blursome.block.exception.BlockErrorCode;
import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

  @Mock
  private BlockRepository blockRepository;
  @Mock
  private MemberRepository memberRepository;
  @Mock
  private ChatRoomService chatRoomService;

  @InjectMocks
  private BlockService blockService;

  private Member member(long id) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + id, "name", "e@e.com", null);
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  @Test
  @DisplayName("정상 차단 시 block 행을 저장한다")
  void block_savesWhenValid() {
    given(memberRepository.existsById(2L)).willReturn(true);
    given(blockRepository.existsByBlockerIdAndBlockedId(1L, 2L)).willReturn(false);
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(memberRepository.getReferenceById(2L)).willReturn(member(2L));

    blockService.block(1L, 2L);

    verify(blockRepository).save(any(Block.class));
  }

  @Test
  @DisplayName("자기 자신 차단은 BLOCK_SELF_NOT_ALLOWED 예외")
  void block_self_throws() {
    assertThatThrownBy(() -> blockService.block(1L, 1L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", BlockErrorCode.BLOCK_SELF_NOT_ALLOWED.getCode());
    verify(blockRepository, never()).save(any());
  }

  @Test
  @DisplayName("대상 회원이 없으면 MEMBER_NOT_FOUND 예외")
  void block_targetNotFound_throws() {
    given(memberRepository.existsById(99L)).willReturn(false);

    assertThatThrownBy(() -> blockService.block(1L, 99L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NOT_FOUND.getCode());
    verify(blockRepository, never()).save(any());
  }

  @Test
  @DisplayName("이미 차단한 상태면 멱등하게 저장하지 않는다")
  void block_alreadyBlocked_isIdempotent() {
    given(memberRepository.existsById(2L)).willReturn(true);
    given(blockRepository.existsByBlockerIdAndBlockedId(1L, 2L)).willReturn(true);

    blockService.block(1L, 2L);

    verify(blockRepository, never()).save(any());
  }

  @Test
  @DisplayName("차단 해제는 삭제를 위임한다")
  void unblock_delegatesDelete() {
    blockService.unblock(1L, 2L);

    verify(blockRepository).deleteByBlockerIdAndBlockedId(1L, 2L);
  }

  @Test
  @DisplayName("차단 시 진행 중 방 동결을 채팅 서비스에 위임한다(#77)")
  void block_freezesRoom() {
    given(memberRepository.existsById(2L)).willReturn(true);
    given(blockRepository.existsByBlockerIdAndBlockedId(1L, 2L)).willReturn(false);
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(memberRepository.getReferenceById(2L)).willReturn(member(2L));

    blockService.block(1L, 2L);

    verify(chatRoomService).freezeRoomOnBlock(1L, 2L);
  }

  @Test
  @DisplayName("이미 차단한 상태여도 방 동결은 멱등하게 다시 위임한다(#77)")
  void block_alreadyBlocked_stillFreezesRoom() {
    given(memberRepository.existsById(2L)).willReturn(true);
    given(blockRepository.existsByBlockerIdAndBlockedId(1L, 2L)).willReturn(true);

    blockService.block(1L, 2L);

    verify(blockRepository, never()).save(any());
    verify(chatRoomService).freezeRoomOnBlock(1L, 2L);
  }

  @Test
  @DisplayName("해제 후 양방향 차단이 모두 풀렸으면 방을 복구한다(#77)")
  void unblock_restoresRoomWhenNoBlockRemains() {
    given(blockRepository.existsBlockBetween(1L, 2L)).willReturn(false);

    blockService.unblock(1L, 2L);

    verify(chatRoomService).restoreRoomOnUnblock(1L, 2L);
  }

  @Test
  @DisplayName("해제 후 반대 방향 차단이 남아 있으면 방을 복구하지 않는다(#77)")
  void unblock_keepsRoomFrozenWhenReverseBlockRemains() {
    given(blockRepository.existsBlockBetween(1L, 2L)).willReturn(true);

    blockService.unblock(1L, 2L);

    verify(chatRoomService, never()).restoreRoomOnUnblock(any(), any());
  }
}
