package com.blursome.blursome.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChatRoomProgressStatusTest {

  @ParameterizedTest(name = "{0} → 공개 장수 {1}")
  @CsvSource({
      "MATCHED, 0",
      "PHOTO_REVEAL_STEP_1, 1",
      "PHOTO_REVEAL_STEP_2, 2",
      "PHOTO_REVEAL_STEP_3, 3",
      "PHOTO_REVEAL_STEP_4, 4",
      "COMPLETED, 5"
  })
  @DisplayName("진행 단계는 공개 원본 장수(0~5)로 매핑된다")
  void revealedOriginalCount(ChatRoomProgressStatus status, int expectedCount) {
    assertThat(status.revealedOriginalCount()).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("공개 장수는 단계가 오를수록 단조 증가한다(선언 순서 보호)")
  void revealedOriginalCount_isMonotonic() {
    ChatRoomProgressStatus[] values = ChatRoomProgressStatus.values();
    for (int i = 1; i < values.length; i++) {
      assertThat(values[i].revealedOriginalCount())
          .isGreaterThan(values[i - 1].revealedOriginalCount());
    }
  }
}
