package com.blursome.blursome.member.event;

import com.blursome.blursome.member.verification.SchoolEmailVerificationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 탈퇴가 커밋된 뒤 회원 도메인의 Redis 상태(진행 중인 학교 이메일 인증 코드)를 지운다(#107). Refresh Token 삭제는 인증
 * 도메인의 리스너({@code RefreshTokenRevocationListener})가 맡는다.
 *
 * <p>삭제가 실패해도 탈퇴는 이미 커밋됐으므로 예외를 전파하지 않고 로그만 남긴다. 남은 코드는 5분 TTL로 만료되며,
 * 탈퇴 회원은 활성 회원 검증에서 막혀 코드를 쓸 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberWithdrawalCleanupListener {

  private final SchoolEmailVerificationStore verificationStore;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMemberWithdrawn(MemberWithdrawnEvent event) {
    try {
      verificationStore.delete(event.memberId());
    } catch (RuntimeException e) {
      log.warn("탈퇴 회원의 학교 이메일 인증 코드 삭제 실패. memberId={}", event.memberId(), e);
    }
  }
}
