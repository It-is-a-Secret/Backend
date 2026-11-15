package com.blursome.blursome.notification.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 기기(브라우저)의 Web Push 구독(이슈 #121).
 *
 * <p>기준 키는 {@code endpoint}다. endpoint는 길어서(최대 2048자) 그대로 유니크 인덱스를 걸면 InnoDB 키 길이 한도를
 * 넘으므로, SHA-256 해시({@code endpoint_hash})에 유니크 제약을 건다. 같은 endpoint를 다른 계정이 등록하면 소유 회원을 그
 * 계정으로 바꾼다(공용 기기 재로그인 시 이전 계정 알림이 새 사용자에게 가지 않게).
 */
@Entity
@Getter
@Table(
    name = "push_subscription",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_push_subscription_endpoint_hash",
        columnNames = "endpoint_hash"
    ),
    indexes = @Index(name = "idx_push_subscription_member", columnList = "member_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscription extends BaseEntity {

  public static final int MAX_ENDPOINT_LENGTH = 2048;
  public static final int MAX_P256DH_LENGTH = 255;
  public static final int MAX_AUTH_LENGTH = 64;
  private static final int MAX_USER_AGENT_LENGTH = 512;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @Column(nullable = false, length = MAX_ENDPOINT_LENGTH)
  private String endpoint;

  @Column(name = "endpoint_hash", nullable = false, length = 64)
  private String endpointHash;

  // 브라우저가 만든 수신용 P-256 공개 키(base64url). 페이로드 암호화에 쓴다.
  @Column(nullable = false, length = MAX_P256DH_LENGTH)
  private String p256dh;

  // 브라우저가 만든 인증 비밀(base64url, 16바이트).
  @Column(nullable = false, length = MAX_AUTH_LENGTH)
  private String auth;

  @Column(length = MAX_USER_AGENT_LENGTH)
  private String userAgent;

  @Column
  private LocalDateTime lastSuccessAt;

  private PushSubscription(Member member, String endpoint, String p256dh, String auth,
      String userAgent) {
    this.member = member;
    this.endpoint = endpoint;
    this.endpointHash = hashEndpoint(endpoint);
    this.p256dh = p256dh;
    this.auth = auth;
    this.userAgent = truncate(userAgent);
  }

  public static PushSubscription register(Member member, String endpoint, String p256dh,
      String auth, String userAgent) {
    return new PushSubscription(member, endpoint, p256dh, auth, userAgent);
  }

  /** 같은 endpoint로 다시 등록되면 키와 소유 회원을 최신 값으로 바꾼다. */
  public void renew(Member member, String p256dh, String auth, String userAgent) {
    this.member = member;
    this.p256dh = p256dh;
    this.auth = auth;
    this.userAgent = truncate(userAgent);
  }

  /** endpoint를 유니크 조회용 해시(SHA-256 hex)로 바꾼다. */
  public static String hashEndpoint(String endpoint) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(endpoint.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }

  private static String truncate(String userAgent) {
    if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
      return userAgent;
    }
    return userAgent.substring(0, MAX_USER_AGENT_LENGTH);
  }
}
