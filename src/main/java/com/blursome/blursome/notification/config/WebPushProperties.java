package com.blursome.blursome.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 표준 Web Push(VAPID) 설정(이슈 #121).
 *
 * <p>키 쌍은 한 번 만들어 고정한다. 바꾸면 브라우저가 이전 공개 키로 만든 구독이 모두 무효가 된다. 공개·개인 키가 비어 있으면
 * 컨텍스트는 뜨지만 푸시 발송을 건너뛰고 공개 키 조회 API는 503을 반환한다(로컬·테스트 환경 기본값).
 *
 * @param vapidPublicKey VAPID 공개 키. 압축하지 않은 P-256 점(65바이트)의 base64url
 * @param vapidPrivateKey VAPID 개인 키. P-256 스칼라(32바이트)의 base64url
 * @param vapidSubject 푸시 서비스가 운영자에게 연락할 주소({@code mailto:} 또는 서비스 URL)
 * @param ttl 푸시 서비스가 오프라인 기기를 위해 메시지를 보관하는 시간({@code TTL} 헤더)
 * @param requestTimeout 푸시 서비스 HTTP 요청 타임아웃
 * @param allowedEndpointHosts 구독 endpoint로 허용할 푸시 서비스 호스트(정확히 같거나 {@code .호스트}로 끝나면 허용).
 *                             서버가 사용자가 보낸 임의 URL로 요청하지 않게 막는다.
 */
@ConfigurationProperties(prefix = "app.push")
public record WebPushProperties(
    String vapidPublicKey,
    String vapidPrivateKey,
    String vapidSubject,
    Duration ttl,
    Duration requestTimeout,
    List<String> allowedEndpointHosts
) {

  /** 공개·개인 키가 모두 설정돼 있을 때만 푸시를 쓴다. */
  public boolean isConfigured() {
    return vapidPublicKey != null && !vapidPublicKey.isBlank()
        && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
  }
}
