package com.blursome.blursome.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP 엔드포인트/브로커 설정(설계 §6-1).
 *
 * <p>브로커는 설계 §6-5의 1단계 — 단일 인스턴스 + 단순 in-memory(Simple) 브로커로 시작한다. 수평 확장 시점에
 * Redis Pub/Sub 릴레이로 전환한다. SockJS 폴백은 초기 미사용(순수 WebSocket).
 *
 * <p>핸드셰이크({@code GET /ws})는 {@code SecurityConfig}에서 permitAll로 열어 두고, 실제 인증은
 * {@link StompAuthChannelInterceptor}가 STOMP {@code CONNECT} 단계에서 담당한다(설계 §6-3).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws");
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // 서버 → 클라이언트 브로드캐스트/개인 채널 prefix
    registry.enableSimpleBroker("/topic", "/queue");
    // 클라이언트 → 서버 (@MessageMapping) prefix
    registry.setApplicationDestinationPrefixes("/app");
    // 개인 대상(/user/queue/...) prefix
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(stompAuthChannelInterceptor);
  }
}
