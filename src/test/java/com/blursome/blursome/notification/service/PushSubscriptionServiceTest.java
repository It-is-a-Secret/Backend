package com.blursome.blursome.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.service.MemberService;
import com.blursome.blursome.notification.config.WebPushProperties;
import com.blursome.blursome.notification.domain.PushSubscription;
import com.blursome.blursome.notification.dto.request.PushSubscriptionRequest;
import com.blursome.blursome.notification.exception.PushErrorCode;
import com.blursome.blursome.notification.repository.PushSubscriptionRepository;
import com.blursome.blursome.notification.support.PushTestKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PushSubscriptionServiceTest {

  private static final Long MEMBER_ID = 1L;
  private static final String APPLE_ENDPOINT = "https://web.push.apple.com/QGuQyavXut";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("Asia/Seoul"));

  private PushSubscriptionRepository repository;
  private MemberService memberService;
  private PushSubscriptionService service;

  private final String p256dh = PushTestKeys.publicKeyBase64Url(PushTestKeys.generateP256());
  private final String auth = PushTestKeys.base64Url(PushTestKeys.randomAuth());

  @BeforeEach
  void setUp() {
    repository = mock(PushSubscriptionRepository.class);
    memberService = mock(MemberService.class);
    service = serviceWith(properties("BPublicKey", "privateKey"));
  }

  @Test
  @DisplayName("처음 보는 endpoint면 새 구독을 저장한다")
  void subscribe_whenNewEndpoint_thenSaves() {
    // given
    Member member = mock(Member.class);
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(member);
    given(repository.findByEndpointHash(anyString())).willReturn(Optional.empty());

    // when
    service.subscribe(MEMBER_ID, request(APPLE_ENDPOINT), "Safari");

    // then
    ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
    verify(repository).save(captor.capture());
    PushSubscription saved = captor.getValue();
    assertThat(saved.getMember()).isSameAs(member);
    assertThat(saved.getEndpoint()).isEqualTo(APPLE_ENDPOINT);
    assertThat(saved.getEndpointHash()).isEqualTo(PushSubscription.hashEndpoint(APPLE_ENDPOINT));
    assertThat(saved.getP256dh()).isEqualTo(p256dh);
    assertThat(saved.getUserAgent()).isEqualTo("Safari");
  }

  @Test
  @DisplayName("같은 endpoint를 다른 계정이 등록하면 새 행을 만들지 않고 소유 회원과 키를 바꾼다")
  void subscribe_whenEndpointOwnedByOther_thenTransfersOwnership() {
    // given
    Member previousOwner = mock(Member.class);
    Member newOwner = mock(Member.class);
    PushSubscription existing = PushSubscription.register(previousOwner, APPLE_ENDPOINT,
        "oldKey", "oldAuth", "old");
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(newOwner);
    given(repository.findByEndpointHash(PushSubscription.hashEndpoint(APPLE_ENDPOINT)))
        .willReturn(Optional.of(existing));

    // when
    service.subscribe(MEMBER_ID, request(APPLE_ENDPOINT), "new");

    // then
    verify(repository, never()).save(any());
    assertThat(existing.getMember()).isSameAs(newOwner);
    assertThat(existing.getP256dh()).isEqualTo(p256dh);
    assertThat(existing.getAuth()).isEqualTo(auth);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://fcm.googleapis.com/fcm/send/abc",       // https 아님
      "https://evil.example.com/push",                // 허용 목록 밖
      "https://fcm.googleapis.com.evil.com/x",        // 접미사 위장
      "https://user@fcm.googleapis.com/x",            // userinfo
      "https://169.254.169.254/latest/meta-data",     // 내부 주소
      "not a url"
  })
  @DisplayName("https가 아니거나 허용된 푸시 서비스 호스트가 아니면 400(PUSH_400_ENDPOINT_NOT_ALLOWED)")
  void subscribe_whenEndpointNotAllowed_thenThrows(String endpoint) {
    assertThatThrownBy(() -> service.subscribe(MEMBER_ID, request(endpoint), null))
        .isInstanceOf(BaseException.class)
        .extracting("code").isEqualTo(PushErrorCode.PUSH_ENDPOINT_NOT_ALLOWED.getCode());
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://fcm.googleapis.com/fcm/send/abc",
      "https://web.push.apple.com/abc",
      "https://updates.push.services.mozilla.com/wpush/v2/abc",
      "https://wns2-par02p.notify.windows.com/w/?token=abc"
  })
  @DisplayName("주요 브라우저 푸시 서비스 endpoint는 허용한다")
  void subscribe_whenKnownPushService_thenAccepted(String endpoint) {
    given(memberService.findActiveMember(MEMBER_ID)).willReturn(mock(Member.class));
    given(repository.findByEndpointHash(anyString())).willReturn(Optional.empty());

    service.subscribe(MEMBER_ID, request(endpoint), null);

    verify(repository).save(any(PushSubscription.class));
  }

  @Test
  @DisplayName("p256dh가 65바이트 비압축 점이 아니거나 auth가 16바이트가 아니면 400(PUSH_400_INVALID_KEYS)")
  void subscribe_whenKeysMalformed_thenThrows() {
    PushSubscriptionRequest badP256dh = new PushSubscriptionRequest(APPLE_ENDPOINT,
        new PushSubscriptionRequest.Keys(PushTestKeys.base64Url(new byte[33]), auth));
    PushSubscriptionRequest badAuth = new PushSubscriptionRequest(APPLE_ENDPOINT,
        new PushSubscriptionRequest.Keys(p256dh, PushTestKeys.base64Url(new byte[8])));
    PushSubscriptionRequest notBase64 = new PushSubscriptionRequest(APPLE_ENDPOINT,
        new PushSubscriptionRequest.Keys("%%%", auth));

    for (PushSubscriptionRequest request : List.of(badP256dh, badAuth, notBase64)) {
      assertThatThrownBy(() -> service.subscribe(MEMBER_ID, request, null))
          .isInstanceOf(BaseException.class)
          .extracting("code").isEqualTo(PushErrorCode.PUSH_INVALID_KEYS.getCode());
    }
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("구독 해제는 내 구독만 endpoint 해시로 지운다(없어도 예외 없음)")
  void unsubscribe_whenCalled_thenDeletesOwnByHash() {
    service.unsubscribe(MEMBER_ID, APPLE_ENDPOINT);

    verify(repository).deleteByEndpointHashAndMemberId(
        PushSubscription.hashEndpoint(APPLE_ENDPOINT), MEMBER_ID);
  }

  @Test
  @DisplayName("로그아웃 기기 해제는 endpoint가 있으면 지우고, 비어 있으면 아무것도 하지 않는다")
  void unsubscribeDevice_whenBlankOrPresent() {
    service.unsubscribeDevice(null);
    service.unsubscribeDevice(" ");
    verifyNoInteractions(repository);

    service.unsubscribeDevice(APPLE_ENDPOINT);
    verify(repository).deleteByEndpointHash(PushSubscription.hashEndpoint(APPLE_ENDPOINT));
  }

  @Test
  @DisplayName("발송 결과 반영: 성공은 마지막 성공 시각 갱신, 만료는 삭제, 빈 목록은 쿼리하지 않는다")
  void recordResults_whenDeliveredAndExpired() {
    service.recordResults(List.of(1L), List.of(2L));
    verify(repository).markDelivered(List.of(1L), LocalDateTime.now(CLOCK));
    verify(repository).deleteAllByIdIn(List.of(2L));

    service.recordResults(List.of(), List.of());
    verify(repository).markDelivered(any(), any());
    verify(repository).deleteAllByIdIn(any());
  }

  @Test
  @DisplayName("공개 키를 반환하고, 키가 설정되지 않았으면 503(PUSH_503_NOT_CONFIGURED)")
  void getPublicKey_whenConfiguredOrNot() {
    assertThat(service.getPublicKey().publicKey()).isEqualTo("BPublicKey");

    PushSubscriptionService unconfigured = serviceWith(properties("", ""));
    assertThatThrownBy(unconfigured::getPublicKey)
        .isInstanceOf(BaseException.class)
        .extracting("code").isEqualTo(PushErrorCode.PUSH_NOT_CONFIGURED.getCode());
  }

  private PushSubscriptionService serviceWith(WebPushProperties properties) {
    return new PushSubscriptionService(repository, memberService, properties, CLOCK);
  }

  private static WebPushProperties properties(String publicKey, String privateKey) {
    return new WebPushProperties(publicKey, privateKey, "mailto:test@blursome.com",
        Duration.ofDays(1), Duration.ofSeconds(5),
        List.of("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com",
            "notify.windows.com"));
  }

  private PushSubscriptionRequest request(String endpoint) {
    return new PushSubscriptionRequest(endpoint, new PushSubscriptionRequest.Keys(p256dh, auth));
  }
}
