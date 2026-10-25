package com.blursome.blursome.auth.token;

import com.blursome.blursome.member.event.MemberWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 탈퇴가 커밋된 뒤 그 회원의 Refresh Token을 Redis에서 지운다(#107).
 *
 * <p>DB 롤백과 어긋나지 않도록 커밋 이후에만 지운다. 삭제가 실패해도 Refresh API가 회원 활성 상태를 다시 확인해
 * {@code MEMBER_403_INACTIVE}로 막으므로, 탈퇴 회원에게 새 토큰이 발급되지 않는다. 그래서 예외를 전파하지 않고 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRevocationListener {

  private final RefreshTokenStore refreshTokenStore;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMemberWithdrawn(MemberWithdrawnEvent event) {
    try {
      refreshTokenStore.delete(event.memberId());
    } catch (RuntimeException e) {
      log.warn("탈퇴 회원의 Refresh Token 삭제 실패. memberId={}", event.memberId(), e);
    }
  }
}
