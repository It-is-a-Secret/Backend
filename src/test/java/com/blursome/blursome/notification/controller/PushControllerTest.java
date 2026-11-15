package com.blursome.blursome.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.notification.dto.request.PushSubscriptionRequest;
import com.blursome.blursome.notification.dto.response.PushPublicKeyResponse;
import com.blursome.blursome.notification.exception.PushErrorCode;
import com.blursome.blursome.notification.service.PushSubscriptionService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = PushController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(PushControllerTest.PrincipalResolverConfig.class)
class PushControllerTest {

  private static final long MEMBER_ID = 100L;
  private static final String ENDPOINT = "https://web.push.apple.com/abc";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PushSubscriptionService pushSubscriptionService;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(JwtAuthentication.of(MEMBER_ID, MemberRole.USER));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("공개 키 조회 시 200과 data.publicKey를 반환한다")
  void handleGetPublicKey() throws Exception {
    given(pushSubscriptionService.getPublicKey()).willReturn(new PushPublicKeyResponse("BKey"));

    mockMvc.perform(get("/api/push/public-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.publicKey").value("BKey"));
  }

  @Test
  @DisplayName("키가 설정되지 않았으면 503과 PUSH_503_NOT_CONFIGURED를 반환한다")
  void handleGetPublicKeyWhenNotConfigured() throws Exception {
    given(pushSubscriptionService.getPublicKey())
        .willThrow(BaseException.from(PushErrorCode.PUSH_NOT_CONFIGURED));

    mockMvc.perform(get("/api/push/public-key"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PUSH_503_NOT_CONFIGURED"));
  }

  @Test
  @DisplayName("PushSubscription.toJSON() 형식으로 등록하면 204, 나머지 필드(expirationTime)는 무시한다")
  void handleSubscribe() throws Exception {
    mockMvc.perform(put("/api/push/subscriptions")
            .header(HttpHeaders.USER_AGENT, "Safari")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"endpoint\":\"" + ENDPOINT + "\",\"expirationTime\":null,"
                + "\"keys\":{\"p256dh\":\"BKey\",\"auth\":\"secret\"}}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(pushSubscriptionService).subscribe(eq(MEMBER_ID),
        eq(new PushSubscriptionRequest(ENDPOINT, new PushSubscriptionRequest.Keys("BKey", "secret"))),
        eq("Safari"));
  }

  @Test
  @DisplayName("keys가 없으면 400을 반환하고 서비스를 호출하지 않는다")
  void handleSubscribeWithoutKeys() throws Exception {
    mockMvc.perform(put("/api/push/subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
        .andExpect(status().isBadRequest());

    verify(pushSubscriptionService, never()).subscribe(any(), any(), any());
  }

  @Test
  @DisplayName("구독 해제 시 204를 반환한다")
  void handleUnsubscribe() throws Exception {
    mockMvc.perform(delete("/api/push/subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
        .andExpect(status().isNoContent());

    verify(pushSubscriptionService).unsubscribe(MEMBER_ID, ENDPOINT);
  }

  @TestConfiguration
  static class PrincipalResolverConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
  }
}
