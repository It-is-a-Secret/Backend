package com.blursome.blursome.member.domain;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.exception.MemberErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 엔티티.
 *
 * <p>소셜 로그인에서 받은 식별 정보({@code provider}, {@code providerId}, {@code name}, {@code email})와,
 * 가입 과정에서 채워지는 온보딩 정보({@code nickName}, {@code schoolEmail}), 상태 필드({@code role},
 * {@code activityStatus}, {@code registrationStatus})로 구성된다. 관심사 키워드는 별도 엔티티
 * ({@code MemberKeyword})로 분리하며, 공개 프로필 정보({@code gender}, {@code birthYear},
 * {@code department}, {@code mbti})는 온보딩 완료 시 생성되는 {@code Feed} 엔티티가 보유한다(중복 제거).
 *
 * <p>가입 단계는 {@code UNVERIFIED → VERIFIED → COMPLETED} 순으로만 전진하며(순차 강제·단조 증가),
 * 모든 상태 전이는 불변식을 검증하는 도메인 메서드로만 수행한다. 설계 근거는 {@code docs/member/MEMBER_DOMAIN.md} 참조.
 */
@Entity
@Getter
@Table(
    name = "member",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_member_provider",
            columnNames = {"provider", "provider_id"}
        ),
        @UniqueConstraint(
            name = "uk_member_school_email",
            columnNames = {"school_email"}
        ),
        @UniqueConstraint(
            name = "uk_member_nickname",
            columnNames = {"nick_name"}
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OAuthProvider provider;

  @Column(name = "provider_id", nullable = false, length = 100)
  private String providerId;

  @Column(nullable = false, length = 50)
  private String name;

  // 카카오 등 소셜에서 이메일 동의를 받지 못하면 null일 수 있다(비즈앱·동의 조건). 식별은 (provider, providerId)로 한다.
  @Column(length = 100)
  private String email;

  @Column(name = "profile_image_url", length = 500)
  private String profileImageUrl;

  @Column(name = "nick_name", length = 30)
  private String nickName;

  @Column(name = "school_email", length = 100)
  private String schoolEmail;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MemberRole role;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_status", nullable = false, length = 20)
  private ActivityStatus activityStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "registration_status", nullable = false, length = 30)
  private RegistrationStatus registrationStatus;

  // 탈퇴 시각. 탈퇴 후 30일 보존·영구 삭제 배치의 기준이며, withdraw()에서 세팅·reactivate()에서 해제한다.
  // BaseEntity.updatedAt은 프로필 갱신 등 임의 수정에도 변하므로 탈퇴 시각 대용으로 쓰지 않는다.
  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  // 마지막 접속·주요 활동 시각. 로그인 시 갱신하며, "최근 접속 우선 노출" 정렬의 기준이 된다.
  @Column(name = "last_active_at")
  private LocalDateTime lastActiveAt;

  @Builder(access = AccessLevel.PRIVATE)
  private Member(
      OAuthProvider provider,
      String providerId,
      String name,
      String email,
      String profileImageUrl,
      MemberRole role,
      ActivityStatus activityStatus,
      RegistrationStatus registrationStatus,
      LocalDateTime lastActiveAt
  ) {
    this.provider = provider;
    this.providerId = providerId;
    this.name = name;
    this.email = email;
    this.profileImageUrl = profileImageUrl;
    this.role = role;
    this.activityStatus = activityStatus;
    this.registrationStatus = registrationStatus;
    this.lastActiveAt = lastActiveAt;
  }

  /**
   * 소셜 로그인 정보로 새 회원을 생성한다. 기본 역할 {@code USER}, 활동 {@code ACTIVE}, 가입 단계 {@code UNVERIFIED}.
   * 온보딩 정보({@code nickName}, {@code schoolEmail})는 후속 단계에서 채워진다.
   * 최초 진입도 활동이므로 {@code lastActiveAt}을 생성 시각으로 초기화한다.
   */
  public static Member createOAuthMember(
      OAuthProvider provider,
      String providerId,
      String name,
      String email,
      String profileImageUrl
  ) {
    return Member.builder()
        .provider(provider)
        .providerId(providerId)
        .name(name)
        .email(email)
        .profileImageUrl(profileImageUrl)
        .role(MemberRole.USER)
        .activityStatus(ActivityStatus.ACTIVE)
        .registrationStatus(RegistrationStatus.UNVERIFIED)
        .lastActiveAt(LocalDateTime.now())
        .build();
  }

  /**
   * 학교 이메일 인증을 완료한다({@code UNVERIFIED → VERIFIED}).
   *
   * @throws BaseException 활성 회원이 아니거나 이미 인증을 마친 단계인 경우
   */
  public void verifySchoolEmail(String schoolEmail) {
    requireActive();
    if (this.registrationStatus.isAtLeast(RegistrationStatus.VERIFIED)) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_VERIFIED);
    }
    this.schoolEmail = schoolEmail;
    this.registrationStatus = RegistrationStatus.VERIFIED;
  }

  /**
   * 온보딩을 완료한다({@code VERIFIED → COMPLETED}). 학교 인증이 선행되어야 한다.
   *
   * <p>닉네임만 회원에 세팅한다. 공개 프로필(생년·학과·MBTI·성별)과 관심사 키워드({@code MemberKeyword})는
   * 별도 애그리거트({@code Feed}, {@code MemberKeyword})로 서비스 계층에서 저장한다
   * (설계 {@code docs/member/MEMBER_ONBOARDING.md}, {@code docs/keyword/KEYWORD_DOMAIN.md}).
   *
   * @throws BaseException 활성 회원이 아니거나, 학교 인증 전이거나, 이미 온보딩을 마친 경우
   */
  public void completeOnboarding(String nickName) {
    requireActive();
    if (this.registrationStatus == RegistrationStatus.UNVERIFIED) {
      throw BaseException.from(MemberErrorCode.MEMBER_SCHOOL_VERIFICATION_REQUIRED);
    }
    if (this.registrationStatus == RegistrationStatus.COMPLETED) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_ONBOARDED);
    }
    this.nickName = nickName;
    this.registrationStatus = RegistrationStatus.COMPLETED;
  }

  /**
   * 재로그인 시 소셜 측에서 변경된 {@code name}·{@code profileImageUrl}만 갱신한다.
   * 온보딩 닉네임({@code nickName})·가입 단계는 건드리지 않는다.
   */
  public void updateProfileFromOAuth(String name, String profileImageUrl) {
    if (name != null && !name.equals(this.name)) {
      this.name = name;
    }
    if (profileImageUrl != null && !profileImageUrl.equals(this.profileImageUrl)) {
      this.profileImageUrl = profileImageUrl;
    }
  }

  /**
   * 회원을 탈퇴 처리한다(소프트삭제, {@code ACTIVE → WITHDRAWN}). 가입 단계·온보딩 정보는 보존한다.
   * 탈퇴 시각({@code withdrawnAt})을 기록해 30일 보존 후 영구 삭제 배치의 기준으로 삼는다.
   *
   * @throws BaseException 이미 탈퇴한 회원인 경우
   */
  public void withdraw() {
    if (this.activityStatus == ActivityStatus.WITHDRAWN) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
    }
    this.activityStatus = ActivityStatus.WITHDRAWN;
    this.withdrawnAt = LocalDateTime.now();
  }

  /**
   * 탈퇴 회원을 재활성화한다({@code WITHDRAWN → ACTIVE}). 가입 단계·온보딩 정보({@code nickName}/
   * {@code schoolEmail})는 직전 상태 그대로 복구하고, 탈퇴 시각({@code withdrawnAt})은 해제한다.
   *
   * @throws BaseException 이미 활성 상태인 경우
   */
  public void reactivate() {
    if (this.activityStatus == ActivityStatus.ACTIVE) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_ACTIVE);
    }
    this.activityStatus = ActivityStatus.ACTIVE;
    this.withdrawnAt = null;
  }

  /**
   * 마지막 접속·주요 활동 시각({@code lastActiveAt})을 현재 시각으로 갱신한다.
   * 로그인 등 활동 시점에 서비스 계층이 호출한다. 상태 전이가 아니므로 활동 상태와 무관하게 동작한다.
   */
  public void recordActivity() {
    this.lastActiveAt = LocalDateTime.now();
  }

  public boolean isActive() {
    return this.activityStatus == ActivityStatus.ACTIVE;
  }

  public boolean isWithdrawn() {
    return this.activityStatus == ActivityStatus.WITHDRAWN;
  }

  public boolean isSchoolVerified() {
    return this.registrationStatus.isAtLeast(RegistrationStatus.VERIFIED);
  }

  public boolean isOnboardingCompleted() {
    return this.registrationStatus == RegistrationStatus.COMPLETED;
  }

  /** 서비스(매칭·채팅 등) 진입 가능 여부 — 활성 + 온보딩 완료. */
  public boolean canUseService() {
    return isActive() && isOnboardingCompleted();
  }

  private void requireActive() {
    if (!isActive()) {
      throw BaseException.from(MemberErrorCode.MEMBER_INACTIVE);
    }
  }
}
