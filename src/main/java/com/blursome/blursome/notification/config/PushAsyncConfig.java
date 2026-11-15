package com.blursome.blursome.notification.config;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 푸시 발송 전용 비동기 실행기(이슈 #121). 외부 푸시 서비스 HTTP 호출이 채팅 송신 스레드를 막지 않도록 커밋 이후 이 풀에서
 * 보낸다.
 *
 * <p>큐가 가득 차면 그 알림은 버리고 로그만 남긴다. 호출 스레드에서 대신 실행(CallerRuns)하면 채팅 송신이 느려지기 때문이다.
 * 푸시는 시스템 알림용 보조 경로이고, 화면 갱신은 STOMP와 다음 목록 조회가 맡는다.
 */
@Slf4j
@Configuration
@EnableAsync
public class PushAsyncConfig {

  public static final String PUSH_EXECUTOR = "pushExecutor";

  @Bean(name = PUSH_EXECUTOR)
  public Executor pushExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("push-");
    executor.setRejectedExecutionHandler((task, pool) ->
        log.warn("푸시 발송 큐가 가득 차 알림 하나를 건너뜁니다."));
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }
}
