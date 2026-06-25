package com.blursome.blursome.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("reactivate()는 정지(SUSPENDED) 회원을 ACTIVE로 해제한다")
  void reactivate_releasesSuspendedMember() {
    Member member = newMember();
    member.suspend();

    member.reactivate();

    assertThat(member.isActive()).isTrue();
    assertThat(member.isSuspended()).isFalse();
  }
}
