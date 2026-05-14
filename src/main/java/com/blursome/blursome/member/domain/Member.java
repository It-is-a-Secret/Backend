package com.blursome.blursome.member.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "member",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_member_provider",
        columnNames = {"provider", "provider_id"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(length = 320)
  private String email;

  @Column(nullable = false, length = 50)
  private String nickname;

  @Column(name = "profile_image_url", length = 500)
  private String profileImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MemberRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MemberStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OAuthProvider provider;

  @Column(name = "provider_id", nullable = false, length = 100)
  private String providerId;

  @Builder(access = AccessLevel.PRIVATE)
  private Member(
      String email,
      String nickname,
      String profileImageUrl,
      MemberRole role,
      MemberStatus status,
      OAuthProvider provider,
      String providerId
  ) {
    this.email = email;
    this.nickname = nickname;
    this.profileImageUrl = profileImageUrl;
    this.role = role;
    this.status = status;
    this.provider = provider;
    this.providerId = providerId;
  }

  /**
   * OAuth 공급자 정보로 새 회원을 생성한다. 기본 역할은 {@code USER}, 상태는 {@code ACTIVE}다.
   */
  public static Member createOAuthMember(
      OAuthProvider provider,
      String providerId,
      String email,
      String nickname,
      String profileImageUrl
  ) {
    return Member.builder()
        .provider(provider)
        .providerId(providerId)
        .email(email)
        .nickname(nickname)
        .profileImageUrl(profileImageUrl)
        .role(MemberRole.USER)
        .status(MemberStatus.ACTIVE)
        .build();
  }

  /**
   * OAuth 동기화 시 닉네임·프로필 이미지가 변경된 경우에만 필드를 갱신한다.
   */
  public void updateProfileFromOAuth(String nickname, String profileImageUrl) {
    if (nickname != null && !nickname.equals(this.nickname)) {
      this.nickname = nickname;
    }
    if (profileImageUrl != null && !profileImageUrl.equals(this.profileImageUrl)) {
      this.profileImageUrl = profileImageUrl;
    }
  }

  public boolean isActive() {
    return this.status == MemberStatus.ACTIVE;
  }
}
