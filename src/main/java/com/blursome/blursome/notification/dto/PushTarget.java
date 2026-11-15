package com.blursome.blursome.notification.dto;

import com.blursome.blursome.notification.domain.PushSubscription;

/** 발송에 필요한 구독 값만 담은 스냅샷. 트랜잭션 밖(발송 스레드)에서 엔티티를 들고 다니지 않으려고 쓴다. */
public record PushTarget(Long subscriptionId, String endpoint, String p256dh, String auth) {

  public static PushTarget from(PushSubscription subscription) {
    return new PushTarget(subscription.getId(), subscription.getEndpoint(),
        subscription.getP256dh(), subscription.getAuth());
  }
}
