package com.blursome.blursome.feed.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse.PresignedUrl;
import com.blursome.blursome.feed.exception.FeedImageErrorCode;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = FeedImageController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(FeedImageControllerTest.PrincipalResolverConfig.class)
class FeedImageControllerTest {

  private static final long MEMBER_ID = 100L;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

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
  @DisplayName("Presigned PUT 발급 요청 시 200과 uploadUrl·originalKey·requiredHeaders를 반환한다")
  void issuePresignedUrls() throws Exception {
    PresignedUrl presignedUrl = new PresignedUrl(
        "https://originals.s3.amazonaws.com/originals/100/abc.jpg",
        "originals/100/abc.jpg",
        Map.of("Content-Type", "image/jpeg", "x-amz-meta-blur-level", "70"));
    given(feedImageService.issuePresignedUploadUrls(eq(MEMBER_ID), any(PresignedUrlRequest.class)))
        .willReturn(new PresignedUrlResponse(List.of(presignedUrl)));

    String body = """
        {"images":[{"fileName":"photo.jpg","contentType":"image/jpeg","blurLevel":70}]}
        """;

    mockMvc.perform(post("/api/feeds/me/images/presigned-urls")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.images[0].uploadUrl")
            .value("https://originals.s3.amazonaws.com/originals/100/abc.jpg"))
        .andExpect(jsonPath("$.data.images[0].originalKey").value("originals/100/abc.jpg"))
        .andExpect(jsonPath("$.data.images[0].requiredHeaders['x-amz-meta-blur-level']").value("70"));
  }

  @Test
  @DisplayName("이미지가 6장을 초과하면 400을 반환한다")
  void rejectTooManyImages() throws Exception {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      if (i > 0) {
        items.append(',');
      }
      items.append("{\"fileName\":\"p").append(i)
          .append(".jpg\",\"contentType\":\"image/jpeg\",\"blurLevel\":70}");
    }
    String body = "{\"images\":[" + items + "]}";

    mockMvc.perform(post("/api/feeds/me/images/presigned-urls")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("이미지 타입이 아닌 contentType이면 400을 반환한다")
  void rejectNonImageContentType() throws Exception {
    String body = """
        {"images":[{"fileName":"doc.pdf","contentType":"application/pdf","blurLevel":70}]}
        """;

    mockMvc.perform(post("/api/feeds/me/images/presigned-urls")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("블러 강도가 하한(50) 미만이면 400을 반환한다")
  void rejectBlurLevelBelowMin() throws Exception {
    String body = """
        {"images":[{"fileName":"photo.jpg","contentType":"image/jpeg","blurLevel":49}]}
        """;

    mockMvc.perform(post("/api/feeds/me/images/presigned-urls")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("메타데이터 저장 요청 시 200과 저장된 이미지 목록을 반환한다")
  void saveImages() throws Exception {
    FeedImageResponse response = new FeedImageResponse(List.of(
        new FeedImageResponse.Image(
            1L, 1, "https://cdn/variants/100/a.jpg", 70, FeedImageProcessingStatus.PROCESSING)));
    given(feedImageService.replaceImages(eq(MEMBER_ID), any(FeedImageSaveRequest.class)))
        .willReturn(response);

    String body = """
        {"images":[{"objectKey":"originals/100/a.png","displayOrder":1,"blurLevel":70}]}
        """;

    mockMvc.perform(post("/api/feeds/me/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.images[0].id").value(1))
        .andExpect(jsonPath("$.data.images[0].displayOrder").value(1))
        .andExpect(jsonPath("$.data.images[0].variantUrl").value("https://cdn/variants/100/a.jpg"))
        .andExpect(jsonPath("$.data.images[0].processingStatus").value("PROCESSING"));
  }

  @Test
  @DisplayName("이미지 목록이 비어 있으면 400을 반환한다")
  void rejectEmptyImages() throws Exception {
    mockMvc.perform(post("/api/feeds/me/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"images\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("displayOrder가 범위(1~5)를 벗어나면 400을 반환한다")
  void rejectDisplayOrderOutOfRange() throws Exception {
    String body = """
        {"images":[{"objectKey":"originals/100/a.png","displayOrder":6,"blurLevel":70}]}
        """;

    mockMvc.perform(post("/api/feeds/me/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("순서가 중복되면 서비스가 던진 FEED_IMAGE_400_DUPLICATE_ORDER로 400을 반환한다")
  void rejectDuplicateOrder() throws Exception {
    given(feedImageService.replaceImages(eq(MEMBER_ID), any(FeedImageSaveRequest.class)))
        .willThrow(BaseException.from(FeedImageErrorCode.DUPLICATE_ORDER));

    String body = """
        {"images":[
          {"objectKey":"originals/100/a.png","displayOrder":1,"blurLevel":70},
          {"objectKey":"originals/100/b.jpg","displayOrder":1,"blurLevel":80}
        ]}
        """;

    mockMvc.perform(post("/api/feeds/me/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("FEED_IMAGE_400_DUPLICATE_ORDER"));
  }

  @TestConfiguration
  static class PrincipalResolverConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
  }
}
