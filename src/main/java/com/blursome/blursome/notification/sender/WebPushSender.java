package com.blursome.blursome.notification.sender;

import com.blursome.blursome.notification.config.WebPushProperties;
import com.blursome.blursome.notification.dto.PushTarget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 표준 Web Push(RFC 8030) 요청을 만들어 푸시 서비스로 보낸다(이슈 #121).
 *
 * <p>페이로드 암호화(aes128gcm, RFC 8291)와 VAPID 서명(RFC 8292)은 {@code web-push} 라이브러리로 만들고, 실제 HTTP
 * 전송은 타임아웃을 직접 통제하려고 JDK {@link HttpClient} 하나를 재사용해 보낸다. Apple({@code web.push.apple.com})
 * endpoint도 같은 방식으로 처리한다(별도 APNs 인증서 불필요).
 *
 * <p>VAPID 키가 설정되지 않았으면 {@link #isEnabled()}가 {@code false}이고 아무것도 보내지 않는다. 키가 설정됐는데
 * 형식이 틀리면 기동 시 실패시킨다(운영에서 조용히 푸시가 끊기는 것을 막기 위해).
 */
@Slf4j
@Component
public class WebPushSender {

  // JDK HttpClient가 직접 관리해 설정할 수 없는 헤더
  private static final Set<String> RESTRICTED_HEADERS = Set.of("content-length", "host",
      "connection", "expect", "upgrade");

  private final WebPushProperties properties;
  private final PushService pushService;
  private final HttpClient httpClient;

  @Autowired
  public WebPushSender(WebPushProperties properties) {
    this(properties, HttpClient.newBuilder()
        .connectTimeout(properties.requestTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  WebPushSender(WebPushProperties properties, HttpClient httpClient) {
    this.properties = properties;
    this.httpClient = httpClient;
    this.pushService = properties.isConfigured() ? createPushService(properties) : null;
    if (pushService == null) {
      log.warn("VAPID 키가 설정되지 않아 Web Push 발송을 건너뜁니다.");
    }
  }

  public boolean isEnabled() {
    return pushService != null;
  }

  /**
   * 구독 하나로 푸시를 보낸다. 실패해도 예외를 던지지 않고 결과로 알린다(채팅 흐름에 영향 없음).
   *
   * @param target 수신 기기 구독
   * @param payload 암호화 전 페이로드(JSON 바이트)
   * @param topic 같은 방 알림을 푸시 서비스에서 합칠 때 쓰는 키(base64url 문자, 32자 이하)
   */
  public PushResult send(PushTarget target, byte[] payload, String topic) {
    if (!isEnabled()) {
      return PushResult.FAILED;
    }
    try {
      Notification notification = Notification.builder()
          .endpoint(target.endpoint())
          .userPublicKey(target.p256dh())
          .userAuth(target.auth())
          .payload(payload)
          .ttl((int) properties.ttl().toSeconds())
          .urgency(Urgency.HIGH)
          .topic(topic)
          .build();
      HttpResponse<Void> response = httpClient.send(toRequest(notification),
          HttpResponse.BodyHandlers.discarding());
      return toResult(target, response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PushResult.FAILED;
    } catch (IOException | GeneralSecurityException | RuntimeException
             | org.jose4j.lang.JoseException e) {
      log.warn("Web Push 발송 실패. subscriptionId={}, host={}", target.subscriptionId(),
          hostOf(target.endpoint()), e);
      return PushResult.FAILED;
    }
  }

  private HttpRequest toRequest(Notification notification)
      throws GeneralSecurityException, IOException, org.jose4j.lang.JoseException {
    HttpPost post = pushService.preparePost(notification, Encoding.AES128GCM);
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(notification.getEndpoint()))
        .timeout(properties.requestTimeout());
    for (Header header : post.getAllHeaders()) {
      if (!RESTRICTED_HEADERS.contains(header.getName().toLowerCase())) {
        builder.header(header.getName(), header.getValue());
      }
    }
    byte[] body = post.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(post.getEntity());
    return builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
  }

  private PushResult toResult(PushTarget target, int status) {
    if (status >= 200 && status < 300) {
      return PushResult.DELIVERED;
    }
    // 404/410: 구독이 만료되거나 해지됐다. 다시 보내도 소용없으므로 구독을 지운다.
    if (status == 404 || status == 410) {
      return PushResult.EXPIRED;
    }
    log.warn("Web Push 응답 오류. subscriptionId={}, host={}, status={}",
        target.subscriptionId(), hostOf(target.endpoint()), status);
    return PushResult.FAILED;
  }

  private static PushService createPushService(WebPushProperties properties) {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    try {
      return new PushService(properties.vapidPublicKey(), properties.vapidPrivateKey(),
          properties.vapidSubject());
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException("VAPID 키 형식이 올바르지 않습니다.", e);
    }
  }

  private static String hostOf(String endpoint) {
    try {
      return URI.create(endpoint).getHost();
    } catch (IllegalArgumentException e) {
      return "unknown";
    }
  }

  /** 발송 결과. */
  public enum PushResult {
    DELIVERED,
    /** 푸시 서비스가 404/410으로 응답해 구독을 지워야 한다. */
    EXPIRED,
    FAILED
  }
}
