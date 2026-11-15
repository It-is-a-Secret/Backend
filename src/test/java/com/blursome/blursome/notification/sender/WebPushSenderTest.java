package com.blursome.blursome.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.notification.config.WebPushProperties;
import com.blursome.blursome.notification.dto.PushTarget;
import com.blursome.blursome.notification.sender.WebPushSender.PushResult;
import com.blursome.blursome.notification.support.PushTestKeys;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 암호화·VAPID 서명으로 로컬 HTTP 서버에 보내 본다. 받은 본문을 RFC 8291 절차로 직접 복호화해 브라우저가 풀 수 있는
 * 형식인지 확인한다.
 */
class WebPushSenderTest {

  private HttpServer server;
  private final AtomicReference<Map<String, List<String>>> receivedHeaders = new AtomicReference<>();
  private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
  private volatile int responseStatus = 201;

  private KeyPair browserKeys;
  private byte[] browserAuth;
  private WebPushSender sender;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/push", exchange -> {
      receivedHeaders.set(exchange.getRequestHeaders());
      receivedBody.set(exchange.getRequestBody().readAllBytes());
      exchange.sendResponseHeaders(responseStatus, -1);
      exchange.close();
    });
    server.start();

    KeyPair vapid = PushTestKeys.generateP256();
    WebPushProperties properties = new WebPushProperties(
        PushTestKeys.publicKeyBase64Url(vapid), PushTestKeys.privateKeyBase64Url(vapid),
        "mailto:test@blursome.com", Duration.ofDays(1), Duration.ofSeconds(5), List.of());
    sender = new WebPushSender(properties);
    browserKeys = PushTestKeys.generateP256();
    browserAuth = PushTestKeys.randomAuth();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  @DisplayName("aes128gcm으로 암호화한 페이로드와 VAPID·TTL·Urgency·Topic 헤더를 보내고 201이면 DELIVERED")
  void send_whenPushServiceAccepts_thenDeliveredWithDecryptablePayload() throws Exception {
    // given
    byte[] payload = "{\"title\":\"닉네임\",\"body\":\"안녕\"}".getBytes(StandardCharsets.UTF_8);

    // when
    PushResult result = sender.send(target(), payload, "room-12");

    // then
    assertThat(result).isEqualTo(PushResult.DELIVERED);
    Map<String, List<String>> headers = receivedHeaders.get();
    assertThat(headers.get("Content-encoding")).containsExactly("aes128gcm");
    assertThat(headers.get("Ttl")).containsExactly(String.valueOf(Duration.ofDays(1).toSeconds()));
    assertThat(headers.get("Urgency")).containsExactly("high");
    assertThat(headers.get("Topic")).containsExactly("room-12");
    assertThat(headers.get("Authorization").get(0)).startsWith("vapid t=");
    assertThat(decrypt(receivedBody.get())).isEqualTo(payload);
  }

  @Test
  @DisplayName("푸시 서비스가 410이나 404를 주면 EXPIRED")
  void send_whenGone_thenExpired() {
    responseStatus = 410;
    assertThat(sender.send(target(), new byte[] {1}, "room-1")).isEqualTo(PushResult.EXPIRED);

    responseStatus = 404;
    assertThat(sender.send(target(), new byte[] {1}, "room-1")).isEqualTo(PushResult.EXPIRED);
  }

  @Test
  @DisplayName("그 밖의 오류 응답이나 연결 실패는 예외 없이 FAILED")
  void send_whenServerErrorOrUnreachable_thenFailed() {
    responseStatus = 500;
    assertThat(sender.send(target(), new byte[] {1}, "room-1")).isEqualTo(PushResult.FAILED);

    PushTarget unreachable = new PushTarget(2L, "http://127.0.0.1:1/push",
        PushTestKeys.publicKeyBase64Url(browserKeys), PushTestKeys.base64Url(browserAuth));
    assertThat(sender.send(unreachable, new byte[] {1}, "room-1")).isEqualTo(PushResult.FAILED);
  }

  @Test
  @DisplayName("VAPID 키가 없으면 비활성이고 보내지 않는다")
  void send_whenNotConfigured_thenDisabled() {
    WebPushSender disabled = new WebPushSender(new WebPushProperties("", "", "mailto:x@y.z",
        Duration.ofDays(1), Duration.ofSeconds(5), List.of()));

    assertThat(disabled.isEnabled()).isFalse();
    assertThat(disabled.send(target(), new byte[] {1}, "room-1")).isEqualTo(PushResult.FAILED);
    assertThat(receivedBody.get()).isNull();
  }

  private PushTarget target() {
    return new PushTarget(1L, "http://127.0.0.1:" + server.getAddress().getPort() + "/push",
        PushTestKeys.publicKeyBase64Url(browserKeys), PushTestKeys.base64Url(browserAuth));
  }

  /** RFC 8291(aes128gcm) 수신 측 복호화. 브라우저가 하는 일을 그대로 한다. */
  private byte[] decrypt(byte[] body) throws Exception {
    ByteBuffer buffer = ByteBuffer.wrap(body);
    byte[] salt = new byte[16];
    buffer.get(salt);
    buffer.getInt(); // record size
    byte[] serverPublic = new byte[buffer.get() & 0xff];
    buffer.get(serverPublic);
    byte[] ciphertext = new byte[buffer.remaining()];
    buffer.get(ciphertext);

    ECPublicKey browserPublic = (ECPublicKey) browserKeys.getPublic();
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(browserKeys.getPrivate());
    agreement.doPhase(toPublicKey(serverPublic, browserPublic), true);
    byte[] ecdhSecret = agreement.generateSecret();

    byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
        PushTestKeys.uncompressed(browserPublic), serverPublic);
    byte[] ikm = hkdf(browserAuth, ecdhSecret, keyInfo, 32);
    byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
    byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] padded = cipher.doFinal(ciphertext);
    int end = padded.length - 1;
    while (padded[end] == 0) {
      end--;
    }
    assertThat(padded[end]).isEqualTo((byte) 0x02); // 마지막 레코드 구분자
    return Arrays.copyOf(padded, end);
  }

  private static PublicKey toPublicKey(byte[] uncompressed, ECPublicKey sameCurve) throws Exception {
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    return KeyFactory.getInstance("EC")
        .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), sameCurve.getParams()));
  }

  private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    byte[] prk = mac.doFinal(ikm);
    mac.init(new SecretKeySpec(prk, "HmacSHA256"));
    byte[] okm = mac.doFinal(concat(info, new byte[] {1}));
    return Arrays.copyOf(okm, length);
  }

  private static byte[] concat(byte[]... parts) {
    int total = Arrays.stream(parts).mapToInt(p -> p.length).sum();
    ByteBuffer out = ByteBuffer.allocate(total);
    for (byte[] part : parts) {
      out.put(part);
    }
    return out.array();
  }
}
