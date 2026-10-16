package com.blursome.blursome.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileEditHistoryTest {

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 12, 0);

  @Test
  @DisplayName("변경한 적 없는 항목은 최근/다음 변경일이 null이고 즉시 변경할 수 있다")
  void empty_isChangeableImmediately() {
    ProfileEditHistory history = ProfileEditHistory.empty();

    for (ProfileItem item : ProfileItem.values()) {
      assertThat(history.lastChangedAt(item)).isNull();
      assertThat(history.nextChangeableAt(item)).isNull();
      assertThat(history.isChangeable(item, T0)).isTrue();
    }
  }

  @Test
  @DisplayName("변경을 기록하면 해당 항목만 갱신되고 다음 변경일은 30일 뒤다")
  void record_updatesOnlyThatItem() {
    ProfileEditHistory history = ProfileEditHistory.empty().record(ProfileItem.MBTI, T0);

    assertThat(history.lastChangedAt(ProfileItem.MBTI)).isEqualTo(T0);
    assertThat(history.nextChangeableAt(ProfileItem.MBTI)).isEqualTo(T0.plusDays(30));
    assertThat(history.lastChangedAt(ProfileItem.NICKNAME)).isNull();
    assertThat(history.lastChangedAt(ProfileItem.KEYWORDS)).isNull();
    assertThat(history.isChangeable(ProfileItem.NICKNAME, T0)).isTrue();
  }

  @Test
  @DisplayName("30일이 지나기 전에는 변경할 수 없고, 정확히 30일이 지나면 변경할 수 있다")
  void isChangeable_boundary() {
    ProfileEditHistory history = ProfileEditHistory.empty().record(ProfileItem.NICKNAME, T0);

    assertThat(history.isChangeable(ProfileItem.NICKNAME, T0.plusDays(30).minusSeconds(1)))
        .isFalse();
    assertThat(history.isChangeable(ProfileItem.NICKNAME, T0.plusDays(30))).isTrue();
  }

  @Test
  @DisplayName("제한 중인 항목 검증은 항목별 에러코드로 실패한다")
  void validateChangeable_throwsItemSpecificError() {
    ProfileEditHistory history = ProfileEditHistory.empty()
        .record(ProfileItem.NICKNAME, T0)
        .record(ProfileItem.KEYWORDS, T0);

    assertThatThrownBy(() -> history.validateChangeable(ProfileItem.NICKNAME, T0.plusDays(1)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NICKNAME_CHANGE_LIMITED.getCode());
    assertThatThrownBy(() -> history.validateChangeable(ProfileItem.KEYWORDS, T0.plusDays(1)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_KEYWORDS_CHANGE_LIMITED.getCode());
  }
}
