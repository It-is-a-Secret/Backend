package com.blursome.blursome.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatRevealPolicyTest {

  private final ChatRevealPolicy policy = new ChatRevealPolicy();

  @ParameterizedTest(name = "min={0} → {1}")
  @CsvSource({
      "0, MATCHED",
      "9, MATCHED",
      "10, PHOTO_REVEAL_STEP_1",
      "19, PHOTO_REVEAL_STEP_1",
      "20, PHOTO_REVEAL_STEP_2",
      "30, PHOTO_REVEAL_STEP_3",
      "40, PHOTO_REVEAL_STEP_4",
      "49, PHOTO_REVEAL_STEP_4",
      "50, COMPLETED",
      "999, COMPLETED"
  })
  @DisplayName("양방향 최소 누적 카운트를 임계값(10/20/30/40/50)에 따라 공개 단계로 매핑한다")
  void statusFor(long min, ChatRoomProgressStatus expected) {
    assertThat(policy.statusFor(min)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "\"{0}\" → 카운트 가능")
  @ValueSource(strings = {"가나다라", "  가나다라  ", "hello"})
  @DisplayName("trim 후 4글자 이상이면 카운트 가능한 콘텐츠다")
  void isCountableContent_true(String content) {
    assertThat(policy.isCountableContent(content)).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\" → 카운트 불가")
  @ValueSource(strings = {"가나다", "  hi  ", "   "})
  @DisplayName("trim 후 4글자 미만이거나 공백뿐이면 카운트할 수 없다")
  void isCountableContent_false(String content) {
    assertThat(policy.isCountableContent(content)).isFalse();
  }

  @Test
  @DisplayName("null 콘텐츠는 카운트할 수 없다")
  void isCountableContent_null() {
    assertThat(policy.isCountableContent(null)).isFalse();
  }
}
