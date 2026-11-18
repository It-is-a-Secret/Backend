package com.blursome.blursome.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberTest {

  private Member newMember() {
    return Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "blur", "blur@example.com", "https://img");
  }

  @Test
  @DisplayName("생성 시 lastActiveAt이 초기화되고 withdrawnAt은 비어 있다")
  void createOAuthMember_initializesLastActiveAt() {
    Member member = newMember();

    assertThat(member.getLastActiveAt()).isNotNull();
    assertThat(member.getWithdrawnAt()).isNull();
  }

  @Test
  @DisplayName("withdraw() 호출 시 WITHDRAWN 전이와 함께 withdrawnAt이 기록된다")
  void withdraw_recordsWithdrawnAt() {
    Member member = newMember();

    member.withdraw();

    assertThat(member.isWithdrawn()).isTrue();
    assertThat(member.getWithdrawnAt()).isNotNull();
  }

  @Test
  @DisplayName("reactivate() 호출 시 ACTIVE로 복구되고 withdrawnAt이 해제된다")
  void reactivate_clearsWithdrawnAt() {
    Member member = newMember();
    member.withdraw();

    member.reactivate();

    assertThat(member.isActive()).isTrue();
    assertThat(member.getWithdrawnAt()).isNull();
  }

  @Test
  @DisplayName("recordActivity() 호출 시 lastActiveAt이 더 최신 시각으로 갱신된다")
  void recordActivity_updatesLastActiveAt() {
    Member member = newMember();
    java.time.LocalDateTime before = member.getLastActiveAt();

    member.recordActivity();

    assertThat(member.getLastActiveAt()).isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("suspend() 호출 시 SUSPENDED로 전이하고 isActive는 false가 된다")
  void suspend_transitionsToSuspendedAndNotActive() {
    Member member = newMember();

    member.suspend();

    assertThat(member.isSuspended()).isTrue();
    assertThat(member.isActive()).isFalse();
  }

  @Test
  @DisplayName("suspend()는 이미 정지된 회원에 멱등(no-op)이다")
  void suspend_isIdempotent() {
    Member member = newMember();
    member.suspend();

    member.suspend();

    assertThat(member.isSuspended()).isTrue();
  }

  @Test
  @DisplayName("suspend()는 탈퇴 회원을 되살리지 않는다(ACTIVE일 때만 전이)")
  void suspend_doesNotResurrectWithdrawn() {
    Member member = newMember();
    member.withdraw();

    member.suspend();

    assertThat(member.isWithdrawn()).isTrue();
    assertThat(member.isSuspended()).isFalse();
  }

  private Member onboardedMember() {
    Member member = newMember();
    member.verifySchoolEmail("blur@gs.anyang.ac.kr");
    member.completeOnboarding("blur-nick");
    return member;
  }

  @Test
  @DisplayName("온보딩 직후에는 프로필 변경 이력이 비어 있어 바로 닉네임을 바꿀 수 있다")
  void changeNickName_firstChangeAfterOnboardingIsAllowed() {
    Member member = onboardedMember();
    LocalDateTime now = LocalDateTime.of(2026, 10, 15, 9, 0);

    member.changeNickName("new-nick", now);

    assertThat(member.getNickName()).isEqualTo("new-nick");
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.NICKNAME)).isEqualTo(now);
    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.MBTI)).isNull();
  }

  @Test
  @DisplayName("닉네임을 바꾼 지 30일이 안 됐으면 다시 바꿀 수 없고 값도 유지된다")
  void changeNickName_withinThirtyDays_rejected() {
    Member member = onboardedMember();
    LocalDateTime now = LocalDateTime.of(2026, 10, 15, 9, 0);
    member.changeNickName("first", now);

    assertThatThrownBy(() -> member.changeNickName("second", now.plusDays(29)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            MemberErrorCode.MEMBER_NICKNAME_CHANGE_LIMITED.getCode());
    assertThat(member.getNickName()).isEqualTo("first");
  }

  @Test
  @DisplayName("항목별 제한이라 닉네임 제한 중에도 MBTI 변경은 기록할 수 있다")
  void recordProfileChange_isPerItem() {
    Member member = onboardedMember();
    LocalDateTime now = LocalDateTime.of(2026, 10, 15, 9, 0);
    member.changeNickName("first", now);

    member.recordProfileChange(ProfileItem.MBTI, now.plusDays(1));

    assertThat(member.profileEditHistory().lastChangedAt(ProfileItem.MBTI))
        .isEqualTo(now.plusDays(1));
  }

  @Test
  @DisplayName("온보딩 전 회원은 프로필을 변경할 수 없다")
  void validateProfileChangeable_requiresOnboarding() {
    Member member = newMember();

    assertThatThrownBy(() -> member.validateProfileChangeable(
        ProfileItem.NICKNAME, LocalDateTime.now()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_ONBOARDING_REQUIRED.getCode());
  }

  @Test
  @DisplayName("임베디드 컬럼이 모두 NULL로 적재돼 이력이 null이어도 빈 이력으로 취급한다")
  void profileEditHistory_nullEmbeddableTreatedAsEmpty() {
    Member member = onboardedMember();
    ReflectionTestUtils.setField(member, "profileEditHistory", null);

    assertThat(member.profileEditHistory().isChangeable(ProfileItem.NICKNAME, LocalDateTime.now()))
        .isTrue();
  }

  @Test
  @DisplayName("reactivate()는 정지(SUSPENDED) 회원을 ACTIVE로 해제한다")
  void reactivate_releasesSuspendedMember() {
    Member member = newMember();
    member.suspend();

    member.reactivate();

    assertThat(member.isActive()).isTrue();
    assertThat(member.isSuspended()).isFalse();
  }

  @Test
  @DisplayName("탈퇴 시각을 주입하면 그 시각이 withdrawnAt으로 기록되고 재가입 가능 시각은 +30일이다(#107)")
  void withdraw_withTime_recordsWithdrawnAtAndRejoinAvailableAt() {
    // given
    Member member = newMember();
    LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 1, 9, 30);

    // when
    member.withdraw(withdrawnAt);

    // then
    assertThat(member.getWithdrawnAt()).isEqualTo(withdrawnAt);
    assertThat(member.rejoinAvailableAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 9, 30));
  }

  @Test
  @DisplayName("재가입 제한은 withdrawnAt+30일 미만이면 참이고 정확히 그 시각부터 거짓이다(#107)")
  void isRejoinRestricted_boundary() {
    // given
    Member member = newMember();
    LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 1, 9, 30);
    member.withdraw(withdrawnAt);
    LocalDateTime availableAt = withdrawnAt.plusDays(30);

    // when & then
    assertThat(member.isRejoinRestricted(availableAt.minusSeconds(1))).isTrue();
    assertThat(member.isRejoinRestricted(availableAt)).isFalse();
    assertThat(member.isRejoinRestricted(availableAt.plusDays(1))).isFalse();
  }

  @Test
  @DisplayName("활성 회원은 재가입 제한 대상이 아니고 재가입 가능 시각도 없다(#107)")
  void isRejoinRestricted_whenActive_thenFalse() {
    // given
    Member member = newMember();

    // when & then
    assertThat(member.isRejoinRestricted(LocalDateTime.now())).isFalse();
    assertThat(member.rejoinAvailableAt()).isNull();
  }

  @Test
  @DisplayName("30일이 지난 탈퇴 회원의 식별자를 해제하면 provider·학교 이메일·개인정보가 비워지고 닉네임은 남는다(#107)")
  void releaseIdentifiers_whenExpired_thenAnonymizes() {
    // given
    Member member = newMember();
    ReflectionTestUtils.setField(member, "id", 7L);
    member.verifySchoolEmail("student@gs.anyang.ac.kr");
    member.completeOnboarding("블러");
    LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 1, 9, 30);
    member.withdraw(withdrawnAt);
    LocalDateTime releasedAt = withdrawnAt.plusDays(30);

    // when
    member.releaseIdentifiers(releasedAt);

    // then
    assertThat(member.getProviderId()).isEqualTo("withdrawn:7");
    assertThat(member.getSchoolEmail()).isNull();
    assertThat(member.getEmail()).isNull();
    assertThat(member.getProfileImageUrl()).isNull();
    assertThat(member.getName()).isEqualTo("탈퇴한 회원");
    assertThat(member.getNickName()).isEqualTo("블러");
    assertThat(member.isWithdrawn()).isTrue();
    assertThat(member.isIdentifiersReleased()).isTrue();
    assertThat(member.getIdentifiersReleasedAt()).isEqualTo(releasedAt);
  }

  @Test
  @DisplayName("재가입 제한 중이거나 활성 회원이면 식별자를 해제할 수 없다(#107)")
  void releaseIdentifiers_whenRestrictedOrActive_thenThrows() {
    // given
    Member active = newMember();
    Member restricted = newMember();
    LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 1, 9, 30);
    restricted.withdraw(withdrawnAt);

    // when & then
    assertThatThrownBy(() -> active.releaseIdentifiers(withdrawnAt))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> restricted.releaseIdentifiers(withdrawnAt.plusDays(30).minusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(restricted.getProviderId()).isEqualTo("kakao-1");
  }

  @Test
  @DisplayName("이미 식별자를 해제했으면 다시 호출해도 바뀌지 않는다(#107)")
  void releaseIdentifiers_whenAlreadyReleased_thenNoOp() {
    // given
    Member member = newMember();
    ReflectionTestUtils.setField(member, "id", 7L);
    LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 1, 9, 30);
    member.withdraw(withdrawnAt);
    LocalDateTime firstRelease = withdrawnAt.plusDays(30);
    member.releaseIdentifiers(firstRelease);

    // when
    member.releaseIdentifiers(firstRelease.plusDays(1));

    // then
    assertThat(member.getIdentifiersReleasedAt()).isEqualTo(firstRelease);
  }
}
