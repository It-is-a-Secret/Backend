package com.blursome.blursome.feed.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.feed.dto.response.PublicFeedImagesResponse;
import com.blursome.blursome.feed.exception.FeedErrorCode;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = FeedController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
class FeedControllerTest {

  private static final long MEMBER_ID = 100L;
  private static final long FEED_ID = 7L;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private FeedImageService feedImageService;

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
  @DisplayName("공개 피드 이미지 조회 시 200과 displayOrder 순서의 블러본 URL 목록을 반환한다")
  void getPublicFeedImages() throws Exception {
    PublicFeedImagesResponse response = new PublicFeedImagesResponse(List.of(
        new PublicFeedImagesResponse.Image(1, "https://cdn/variants/100/a.jpg"),
        new PublicFeedImagesResponse.Image(2, "https://cdn/variants/100/b.jpg")));
    given(feedImageService.getPublicFeedImages(eq(FEED_ID))).willReturn(response);

    mockMvc.perform(get("/api/feeds/{feedId}/images", FEED_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.images[0].displayOrder").value(1))
        .andExpect(jsonPath("$.data.images[0].variantUrl").value("https://cdn/variants/100/a.jpg"))
        .andExpect(jsonPath("$.data.images[1].displayOrder").value(2));
  }

  @Test
  @DisplayName("게이트 미통과(미존재/처리중/0장) 시 서비스가 던진 FEED_404_NOT_FOUND로 404를 반환한다")
  void getPublicFeedImages_notViewable() throws Exception {
    given(feedImageService.getPublicFeedImages(eq(FEED_ID)))
        .willThrow(BaseException.from(FeedErrorCode.FEED_NOT_FOUND));

    mockMvc.perform(get("/api/feeds/{feedId}/images", FEED_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FEED_404_NOT_FOUND"));
  }
}
