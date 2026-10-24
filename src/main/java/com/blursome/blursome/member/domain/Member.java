package com.blursome.blursome.member.domain;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.exception.MemberErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

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
 *
 * <p>{@code @DynamicUpdate}로 바뀐 컬럼만 UPDATE한다. 탈퇴(#107)·학교 인증·신고 정지처럼 같은 회원 행을 다른
 * 트랜잭션이 고칠 수 있어, 전체 컬럼을 쓰면 먼저 읽어 둔 이전 활동 상태로 탈퇴를 덮어쓸 수 있다.
 */
@Entity
@Getter
@DynamicUpdate
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

  /** 탈퇴 후 같은 카카오 계정·학교 이메일로 다시 가입할 수 없는 기간(#107). */
  public static final Duration REJOIN_RESTRICTION = Duration.ofDays(30);

  /** 식별자를 해제한 탈퇴 회원의 {@code providerId} 접두사. 원래 공급자 ID와 겹치지 않는 값으로 유니크를 유지한다. */
  static final String RELEASED_PROVIDER_ID_PREFIX = "withdrawn:";

  /** 식별자를 해제한 탈퇴 회원의 표시 이름. {@code name}은 not null이라 원문 대신 이 값을 둔다. */
  static final String RELEASED_NAME = "탈퇴한 회원";

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

  // 탈퇴 시각. 30일 재가입 제한(#107)과 식별자 해제의 기준이며, withdraw()에서 세팅·reactivate()에서 해제한다.
  // BaseEntity.updatedAt은 프로필 갱신 등 임의 수정에도 변하므로 탈퇴 시각 대용으로 쓰지 않는다.
  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  // 탈퇴 후 30일이 지나 카카오 식별자·학교 이메일·개인정보를 해제(익명화)한 시각(#107). null이면 아직 해제 전이다.
  // 행 자체는 채팅·신고 등 FK 이력 보존을 위해 남긴다.
  @Column(name = "identifiers_released_at")
  private LocalDateTime identifiersReleasedAt;

  // 마지막 접속·주요 활동 시각. 로그인 시 갱신하며, "최근 접속 우선 노출" 정렬의 기준이 된다.
  @Column(name = "last_active_at")
  private LocalDateTime lastActiveAt;

  // 프로필 항목별 최근 변경 시각(#95, 항목별 30일 제한). 컬럼이 모두 NULL이면 Hibernate가 임베디드를 null로
  // 적재하므로 직접 읽지 않고 profileEditHistory()로 접근한다.
  @Getter(AccessLevel.NONE)
  @Embedded
  private ProfileEditHistory profileEditHistory = ProfileEditHistory.empty();

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

  /** 프로필 항목별 최근 변경 이력(#95). 변경한 적이 없으면 빈 이력. */
  public ProfileEditHistory profileEditHistory() {
    if (this.profileEditHistory == null) {
      this.profileEditHistory = ProfileEditHistory.empty();
    }
    return this.profileEditHistory;
  }

  /**
   * 온보딩 이후 프로필 항목을 변경할 수 있는지 검증한다(#95). 여러 항목을 한 번에 바꿀 때 하나라도 막히면
   * 아무것도 반영하지 않도록, 서비스는 반영 전에 바뀐 항목 전부를 이 메서드로 먼저 검증한다.
   *
   * @throws BaseException 활성 회원이 아니거나, 온보딩 전이거나, 마지막 변경 후 30일이 지나지 않은 경우
   */
  public void validateProfileChangeable(ProfileItem item, LocalDateTime now) {
    requireActive();
    if (!isOnboardingCompleted()) {
      throw BaseException.from(MemberErrorCode.MEMBER_ONBOARDING_REQUIRED);
    }
    profileEditHistory().validateChangeable(item, now);
  }

  /**
   * 프로필 항목 변경을 기록한다(#95). 실제 값은 항목 소유 애그리거트(닉네임은 본 엔티티, MBTI는 {@code Feed},
   * 관심사는 {@code MemberKeyword})가 바꾸고, 변경 주기 정책은 회원이 보유한다.
   *
   * @throws BaseException {@link #validateProfileChangeable}과 동일
   */
  public void recordProfileChange(ProfileItem item, LocalDateTime now) {
    validateProfileChangeable(item, now);
    this.profileEditHistory = profileEditHistory().record(item, now);
  }

  /**
   * 온보딩 이후 닉네임을 변경한다(#95). 30일 제한을 검증하고 변경 시각을 기록한다. 유니크 충돌 변환과
   * {@code Feed.nickName} 동기화는 서비스 계층이 담당한다.
   *
   * @throws BaseException {@link #validateProfileChangeable}과 동일
   */
  public void changeNickName(String nickName, LocalDateTime now) {
    recordProfileChange(ProfileItem.NICKNAME, now);
    this.nickName = nickName;
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

  /** 현재 시각으로 탈퇴 처리한다. 시각을 제어해야 하는 서비스는 {@link #withdraw(LocalDateTime)}를 쓴다. */
  public void withdraw() {
    withdraw(LocalDateTime.now());
  }

  /**
   * 회원을 탈퇴 처리한다(소프트삭제, {@code → WITHDRAWN}). 가입 단계·온보딩 정보는 보존한다.
   * 탈퇴 시각({@code withdrawnAt})을 기록해 30일 재가입 제한과 식별자 해제의 기준으로 삼는다(#107).
   *
   * @throws BaseException 이미 탈퇴한 회원인 경우
   */
  public void withdraw(LocalDateTime now) {
    if (this.activityStatus == ActivityStatus.WITHDRAWN) {
      throw BaseException.from(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
    }
    this.activityStatus = ActivityStatus.WITHDRAWN;
    this.withdrawnAt = now;
  }

  /**
   * 같은 카카오 계정·학교 이메일로 다시 가입할 수 있게 되는 시각({@code withdrawnAt + 30일}, #107). 탈퇴 회원이 아니면
   * {@code null}.
   */
  public LocalDateTime rejoinAvailableAt() {
    if (!isWithdrawn() || this.withdrawnAt == null) {
      return null;
    }
    return this.withdrawnAt.plus(REJOIN_RESTRICTION);
  }

  /**
   * 탈퇴 후 재가입 제한 기간인지 여부(#107). {@code withdrawnAt + 30일} <b>미만</b>이면 제한하고, 정확히 그 시각부터
   * 허용한다.
   */
  public boolean isRejoinRestricted(LocalDateTime now) {
    LocalDateTime availableAt = rejoinAvailableAt();
    return availableAt != null && now.isBefore(availableAt);
  }

  /** 카카오 식별자·학교 이메일 등 재가입에 쓰는 식별자를 이미 해제했는지 여부(#107). */
  public boolean isIdentifiersReleased() {
    return this.identifiersReleasedAt != null;
  }

  /**
   * 재가입 제한이 끝난 탈퇴 회원의 식별자와 개인정보를 해제(익명화)한다(#107). 행은 채팅·신고 등 FK 이력 보존을 위해
   * 남기고, {@code (provider, providerId)}·{@code schoolEmail} 유니크 값을 비워 같은 카카오 계정과 학교 이메일로
   * 새로 가입할 수 있게 한다. 이미 해제했으면 멱등(no-op).
   *
   * <p>닉네임은 지난 채팅 이력에서 상대를 식별하는 표시명으로 남긴다.
   *
   * @throws IllegalStateException 탈퇴 회원이 아니거나 아직 재가입 제한 기간인 경우
   */
  public void releaseIdentifiers(LocalDateTime now) {
    if (isIdentifiersReleased()) {
      return;
    }
    if (!isWithdrawn() || isRejoinRestricted(now)) {
      throw new IllegalStateException("재가입 제한이 끝난 탈퇴 회원만 식별자를 해제할 수 있습니다: " + this.id);
    }
    this.providerId = RELEASED_PROVIDER_ID_PREFIX + this.id;
    this.schoolEmail = null;
    this.email = null;
    this.name = RELEASED_NAME;
    this.profileImageUrl = null;
    this.identifiersReleasedAt = now;
  }

  /**
   * 비활성 회원을 재활성화한다({@code WITHDRAWN}/{@code SUSPENDED → ACTIVE}). 신고 제재로 정지된 회원의 운영자 수동
   * 해제(#75)에 사용한다. 탈퇴 회원의 재가입은 30일 제한 뒤 새 회원 생성으로 처리하므로(#107) 로그인 경로에서는 더 이상
   * 호출하지 않는다. 가입 단계·온보딩 정보
   * ({@code nickName}/{@code schoolEmail})는 직전 상태 그대로 복구하고, 탈퇴 시각({@code withdrawnAt})은
   * 해제한다.
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
   * 회원을 신고 누적 제재로 임시 정지한다({@code ACTIVE → SUSPENDED}, #75). 정지 회원은
   * {@link #isActive()}가 {@code false}라 로그인·채팅·탐색 게이트에서 모두 차단된다. 이미 정지된 회원은
   * 멱등(no-op)이며, 탈퇴({@code WITHDRAWN}) 회원은 되살리지 않으려고 그대로 둔다(ACTIVE일 때만 전이).
   */
  public void suspend() {
    if (this.activityStatus == ActivityStatus.ACTIVE) {
      this.activityStatus = ActivityStatus.SUSPENDED;
    }
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

  /** 신고 누적 제재로 임시 정지된 상태인지 여부(#75). */
  public boolean isSuspended() {
    return this.activityStatus == ActivityStatus.SUSPENDED;
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
