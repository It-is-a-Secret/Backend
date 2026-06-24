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
}
