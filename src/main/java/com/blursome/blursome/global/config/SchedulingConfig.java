package com.blursome.blursome.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring {@code @Scheduled} 기반 주기 작업을 활성화한다. 블러본 처리 상태 폴링 스케줄러(#51)가 이 설정에
 * 의존한다.
 *
 * <p>단일 서버 기준 단순 {@code @Scheduled}로 동작한다. 다중 인스턴스 전환 시 중복 실행 방지(분산 락)는 v1
 * 범위가 아니며 별도 이슈로 도입한다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-1)
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
