package com.blursome.blursome.notification.event;

import com.blursome.blursome.member.event.MemberWithdrawnEvent;
import com.blursome.blursome.notification.service.PushSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 탈퇴가 커밋된 뒤 그 회원의 모든 기기 구독을 지운다(이슈 #121).
 *
 * <p>삭제가 실패해도 탈퇴는 이미 커밋됐으므로 예외를 전파하지 않는다. 탈퇴 회원의 방은 모두 종료돼 발송 조건(방
 * {@code ACTIVE})에서 걸러지므로 남은 구독으로 채팅 푸시가 나가지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushSubscriptionWithdrawalListener {

  private final PushSubscriptionService pushSubscriptionService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMemberWithdrawn(MemberWithdrawnEvent event) {
    try {
      pushSubscriptionService.unsubscribeAll(event.memberId());
    } catch (RuntimeException e) {
      log.warn("탈퇴 회원의 푸시 구독 삭제 실패. memberId={}", event.memberId(), e);
    }
  }
}
