package com.blursome.blursome.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 의존 로직을 테스트에서 제어할 수 있도록 {@link Clock}을 빈으로 노출한다. 사진 공개 단계의 발신자별
 * 디바운스(이슈 #79) 등 "현재 시각"이 판정에 들어가는 로직이 {@code LocalDateTime.now()}를 직접 부르지 않고
 * 주입된 {@code Clock}을 쓰면, 테스트가 고정/오프셋 {@code Clock}으로 시간을 통제할 수 있다.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
