package com.blursome.blursome.global.security;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(handler ->
            handler.authenticationEntryPoint(jwtAuthenticationEntryPoint))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST,
                "/api/auth/token/refresh",
                "/api/auth/logout").permitAll()
            // 카카오 로그인 진입·콜백(서버 주도 리다이렉트 플로우)
            .requestMatchers(HttpMethod.GET,
                "/api/auth/oauth/kakao/authorize",
                "/api/auth/oauth/kakao/callback").permitAll()
            // 배포 헬스체크(ping/pong)
            .requestMatchers(HttpMethod.GET, "/api/ping").permitAll()
            // 키워드 카탈로그(공개 참조 데이터 — 온보딩 선택 화면용)
            .requestMatchers(HttpMethod.GET, "/api/keywords").permitAll()
            .requestMatchers("/error").permitAll()
            // STOMP 핸드셰이크. 실제 인증은 STOMP CONNECT 단계의 ChannelInterceptor가 담당한다(설계 §6-1, §6-3).
            .requestMatchers("/ws/**").permitAll()
            .requestMatchers(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter,
            UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

    // 운영 프론트 도메인 + 로컬 개발용만 허용.
    // RefreshToken은 HttpOnly Set-Cookie로 내려가므로 allowCredentials 필요(쿠키 송수신용).
    // AccessToken은 응답 바디로 내려가고 응답 헤더로 노출하는 토큰이 없으므로 exposedHeaders는 두지 않는다.
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(
        "https://front-theta-three-32.vercel.app",   // 운영 프론트 도메인
        "http://localhost:5173"                       // 로컬 개발용
    ));
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(Arrays.asList("*"));
    configuration.setAllowCredentials(true);
    source.registerCorsConfiguration("/**", configuration);

    return source;
  }
}
