package com.blursome.blursome.member.service;

import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.event.MemberWithdrawnEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 유스케이스를 조율하는 Facade 서비스(#107).
 *
 * <p>한 트랜잭션에서 회원을 {@code WITHDRAWN}으로 바꾸고 참여 중인 대화를 끝낸다. 회원 행은 물리 삭제하지 않으며 피드·사진·
 * 메시지·신고/차단 이력·S3 객체도 지우지 않는다. 탈퇴 회원은 탐색·공개 피드·카드 상세·채팅 개설 게이트에서 활성 회원이 아니라
 * 자연히 빠진다. Redis의 Refresh Token·학교 이메일 인증 코드는 커밋 이후 {@link MemberWithdrawnEvent} 리스너가 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberWithdrawalService {

  private final MemberService memberService;
  private final ChatRoomService chatRoomService;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * 인증 회원 본인을 탈퇴시킨다.
   *
   * <p>회원 행을 쓰기 락으로 먼저 잡아 같은 회원의 동시 탈퇴·로그인·학교 인증·프로필 수정과 직렬화한다. 이어서 채팅방 행을
   * {@code roomId} 오름차순으로 잠근다(회원 → 방 순서). 메시지 송신·방 나가기·차단 동결/해제는 방 행만 잠그고, 신규 채팅
   * 생성은 두 회원 행을 id 오름차순으로 잠근 뒤 새 방만 쓰므로 잠금 순서가 엇갈리지 않는다. 신규 채팅 생성이 회원 락을 먼저
   * 커밋하면 이 트랜잭션은 락을 얻은 뒤 참여 방을 조회하므로 그 방까지 종료한다.
   *
   * <p>활성 회원만 탈퇴할 수 있다. 이미 탈퇴했거나(중복 요청) 신고 누적으로 정지된 회원은 {@code MEMBER_403_INACTIVE}다.
   * 정지 회원의 탈퇴를 막는 이유는 탈퇴 후 30일 뒤 새 계정으로 제재를 우회하지 못하게 하기 위함이다.
   *
   * @throws BaseException 회원이 없거나({@code MEMBER_404_NOT_FOUND}) 활성 회원이 아닌 경우({@code MEMBER_403_INACTIVE})
   */
  @Transactional
  public void withdraw(Long memberId) {
    Member member = memberService.findActiveMemberForUpdate(memberId);
    member.withdraw(LocalDateTime.now(clock));
    chatRoomService.closeRoomsOnMemberWithdrawal(memberId);
    eventPublisher.publishEvent(new MemberWithdrawnEvent(memberId));
  }
}
