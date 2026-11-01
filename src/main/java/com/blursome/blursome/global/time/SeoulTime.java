package com.blursome.blursome.global.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 클라이언트에 내려가는 시각의 기준 시간대(한국 시간, {@code +09:00}) 변환 유틸(#113).
 *
 * <p>엔티티 시각({@code BaseEntity.createdAt}, {@code LocalDateTime.now(clock)} 등)은 시간대가 없는
 * {@link LocalDateTime}이며 <b>서버 JVM 기본 시간대</b>의 벽시계 값이다. 운영 컨테이너는 UTC, 로컬은 KST처럼 서버마다
 * 기본 시간대가 다를 수 있으므로, 응답으로 내보낼 때는 서버 시간대 기준으로 순간(instant)을 확정한 뒤 한국 시간
 * 오프셋 시각으로 바꾼다. 서버 시간대가 무엇이든 같은 순간이면 같은 결과가 나온다.
 */
public final class SeoulTime {

  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private SeoulTime() {
  }

  /** 서버 기본 시간대의 {@link LocalDateTime}을 같은 순간의 한국 시간 오프셋 시각으로 변환한다. */
  public static OffsetDateTime toOffset(LocalDateTime serverLocalDateTime) {
    return toOffset(serverLocalDateTime, ZoneId.systemDefault());
  }

  /**
   * {@code sourceZone} 기준의 {@link LocalDateTime}을 같은 순간의 한국 시간 오프셋 시각으로 변환한다. 주입된
   * {@code Clock}으로 시각을 만드는 서비스는 {@code clock.getZone()}을 넘겨 그 기준을 그대로 따른다.
   */
  public static OffsetDateTime toOffset(LocalDateTime localDateTime, ZoneId sourceZone) {
    return localDateTime.atZone(sourceZone)
        .withZoneSameInstant(ZONE)
        .toOffsetDateTime();
  }
}
