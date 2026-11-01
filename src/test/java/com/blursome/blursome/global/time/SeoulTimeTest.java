package com.blursome.blursome.global.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeoulTimeTest {

  @Test
  @DisplayName("UTC 기준 시각은 같은 순간의 한국 시간(+09:00)으로 바뀐다")
  void toOffset_fromUtc_shiftsNineHours() {
    LocalDateTime utc = LocalDateTime.of(2026, 10, 28, 4, 22, 14, 828_729_000);

    assertThat(SeoulTime.toOffset(utc, ZoneOffset.UTC).toString())
        .isEqualTo("2026-10-28T13:22:14.828729+09:00");
  }

  @Test
  @DisplayName("한국 시간 기준 시각은 벽시계 값을 유지하고 오프셋만 붙는다")
  void toOffset_fromSeoul_keepsWallClock() {
    LocalDateTime seoul = LocalDateTime.of(2026, 10, 28, 13, 22, 14, 828_729_000);

    assertThat(SeoulTime.toOffset(seoul, ZoneId.of("Asia/Seoul")).toString())
        .isEqualTo("2026-10-28T13:22:14.828729+09:00");
  }
}
