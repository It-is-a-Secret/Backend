package com.blursome.blursome.notification.service;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.service.MemberService;
import com.blursome.blursome.notification.config.WebPushProperties;
import com.blursome.blursome.notification.domain.PushSubscription;
import com.blursome.blursome.notification.dto.PushTarget;
import com.blursome.blursome.notification.dto.request.PushSubscriptionRequest;
import com.blursome.blursome.notification.dto.response.PushPublicKeyResponse;
import com.blursome.blursome.notification.exception.PushErrorCode;
import com.blursome.blursome.notification.repository.PushSubscriptionRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기기 단위 Web Push 구독을 관리한다(이슈 #121). 계정 단위 알림 on/off는 두지 않는다 — 알림 끄기는 기기 구독 해제다.
 *
 * <p>구독의 기준 키는 endpoint다. 등록은 upsert라 앱을 시작할 때마다 다시 보내도 멱등이고, 같은 endpoint를 다른 계정이
 * 등록하면 소유 회원이 그 계정으로 바뀐다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PushSubscriptionService {

  private static final int P256DH_LENGTH = 65;
  private static final byte UNCOMPRESSED_POINT_PREFIX = 0x04;
  private static final int AUTH_LENGTH = 16;

  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final MemberService memberService;
  private final WebPushProperties properties;
  private final Clock clock;

  /**
   * VAPID 공개 키를 반환한다.
   *
   * @throws BaseException 키가 설정되지 않은 환경이면 {@link PushErrorCode#PUSH_NOT_CONFIGURED}
   */
  public PushPublicKeyResponse getPublicKey() {
    if (!properties.isConfigured()) {
      throw BaseException.from(PushErrorCode.PUSH_NOT_CONFIGURED);
    }
    return new PushPublicKeyResponse(properties.vapidPublicKey());
  }

  /**
   * 이 기기 구독을 등록하거나 갱신한다(upsert, 멱등).
   *
   * @throws BaseException 허용하지 않는 푸시 서비스 주소({@link PushErrorCode#PUSH_ENDPOINT_NOT_ALLOWED}),
   *     키 형식 오류({@link PushErrorCode#PUSH_INVALID_KEYS}), 비활성 회원
   */
  @Transactional
  public void subscribe(Long memberId, PushSubscriptionRequest request, String userAgent) {
    String endpoint = request.endpoint().strip();
    validateEndpoint(endpoint);
    validateKeys(request.keys().p256dh(), request.keys().auth());
    Member member = memberService.findActiveMember(memberId);

    pushSubscriptionRepository.findByEndpointHash(PushSubscription.hashEndpoint(endpoint))
        .ifPresentOrElse(
            existing -> existing.renew(member, request.keys().p256dh(), request.keys().auth(),
                userAgent),
            () -> pushSubscriptionRepository.save(PushSubscription.register(member, endpoint,
                request.keys().p256dh(), request.keys().auth(), userAgent)));
  }

  /** 내 이 기기 구독을 해제한다. 없거나 남의 구독이면 아무것도 하지 않는다(멱등). */
  @Transactional
  public void unsubscribe(Long memberId, String endpoint) {
    pushSubscriptionRepository.deleteByEndpointHashAndMemberId(
        PushSubscription.hashEndpoint(endpoint.strip()), memberId);
  }

  /**
   * 로그아웃하는 기기의 구독을 해제한다. 로그아웃은 Access Token 없이(만료 RT로도) 호출되므로 회원과 무관하게 endpoint로
   * 지운다. endpoint는 그 브라우저만 아는 값이라 남의 구독을 지울 위험이 낮고, 로그아웃한 기기로 알림이 계속 가는 것을 막는
   * 쪽이 더 중요하다. 값이 비어 있으면 아무것도 하지 않는다.
   */
  @Transactional
  public void unsubscribeDevice(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    pushSubscriptionRepository.deleteByEndpointHash(PushSubscription.hashEndpoint(endpoint.strip()));
  }

  /**
   * 회원의 모든 기기 구독을 지운다(탈퇴). 탈퇴 커밋 이후({@code AFTER_COMMIT}) 리스너에서 호출되는데, 그 시점에는 끝난
   * 트랜잭션의 리소스가 아직 묶여 있어 일반 전파로는 삭제가 커밋되지 않으므로 새 트랜잭션에서 실행한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void unsubscribeAll(Long memberId) {
    pushSubscriptionRepository.deleteAllByMemberId(memberId);
  }

  /** 회원의 발송 대상 기기 목록. */
  public List<PushTarget> findTargets(Long memberId) {
    return pushSubscriptionRepository.findAllByMemberId(memberId).stream()
        .map(PushTarget::from)
        .toList();
  }

  /** 발송 결과를 반영한다: 성공한 구독은 마지막 성공 시각을 남기고, 만료(404/410) 구독은 지운다. */
  @Transactional
  public void recordResults(Collection<Long> deliveredIds, Collection<Long> expiredIds) {
    if (!deliveredIds.isEmpty()) {
      pushSubscriptionRepository.markDelivered(deliveredIds, LocalDateTime.now(clock));
    }
    if (!expiredIds.isEmpty()) {
      pushSubscriptionRepository.deleteAllByIdIn(expiredIds);
    }
  }

  /** https이고 알려진 푸시 서비스 호스트일 때만 허용한다. 서버가 사용자가 넣은 임의 주소로 요청하지 않게 막는다. */
  private void validateEndpoint(String endpoint) {
    String host;
    try {
      URI uri = new URI(endpoint);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw BaseException.from(PushErrorCode.PUSH_ENDPOINT_NOT_ALLOWED);
      }
      host = uri.getHost().toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      throw BaseException.from(PushErrorCode.PUSH_ENDPOINT_NOT_ALLOWED);
    }
    List<String> allowed = properties.allowedEndpointHosts();
    boolean matched = allowed != null && allowed.stream()
        .map(h -> h.toLowerCase(Locale.ROOT))
        .anyMatch(h -> host.equals(h) || host.endsWith("." + h));
    if (!matched) {
      throw BaseException.from(PushErrorCode.PUSH_ENDPOINT_NOT_ALLOWED);
    }
  }

  /** p256dh는 압축하지 않은 P-256 점(65바이트, 0x04 시작), auth는 16바이트여야 한다. */
  private void validateKeys(String p256dh, String auth) {
    byte[] publicKey = decodeBase64Url(p256dh);
    byte[] authSecret = decodeBase64Url(auth);
    if (publicKey.length != P256DH_LENGTH || publicKey[0] != UNCOMPRESSED_POINT_PREFIX
        || authSecret.length != AUTH_LENGTH) {
      throw BaseException.from(PushErrorCode.PUSH_INVALID_KEYS);
    }
  }

  private static byte[] decodeBase64Url(String value) {
    try {
      return Base64.getUrlDecoder().decode(value.strip());
    } catch (IllegalArgumentException e) {
      throw BaseException.from(PushErrorCode.PUSH_INVALID_KEYS);
    }
  }
}
