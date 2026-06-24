package com.blursome.blursome.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeedImageServiceTest {

  private static final long MEMBER_ID = 100L;

  @Mock
  private S3ObjectKeyGenerator keyGenerator;

  @Mock
  private S3StorageService storageService;

  @InjectMocks
  private FeedImageService feedImageService;

  @Test
  @DisplayName("파일 확장자로 원본 key를 만들고 지정 blurLevel로 Presigned PUT을 발급한다")
  void issuePresignedUploadUrls() {
    String originalKey = "originals/100/uuid.png";
    given(keyGenerator.generateOriginalKey(MEMBER_ID, "png")).willReturn(originalKey);
    given(storageService.presignOriginalUpload(originalKey, "image/png", 70))
        .willReturn(new PresignedUpload("https://upload-url", Map.of("x-amz-meta-blur-level", "70")));

    PresignedUrlResponse response = feedImageService.issuePresignedUploadUrls(
        MEMBER_ID,
        new PresignedUrlRequest(List.of(new ImageRequest("photo.png", "image/png", 70))));

    assertThat(response.images()).hasSize(1);
    assertThat(response.images().get(0).originalKey()).isEqualTo(originalKey);
    assertThat(response.images().get(0).uploadUrl()).isEqualTo("https://upload-url");
  }

  @Test
  @DisplayName("blurLevel을 생략하면 기본값(80)으로 발급한다")
  void issueWithDefaultBlurLevel() {
    given(keyGenerator.generateOriginalKey(eq(MEMBER_ID), any())).willReturn("originals/100/uuid.jpg");
    given(storageService.presignOriginalUpload(any(), eq("image/jpeg"), eq(FeedImage.DEFAULT_BLUR_LEVEL)))
        .willReturn(new PresignedUpload("https://upload-url", Map.of()));

    feedImageService.issuePresignedUploadUrls(
        MEMBER_ID,
        new PresignedUrlRequest(List.of(new ImageRequest("photo.jpg", "image/jpeg", null))));

    verify(storageService).presignOriginalUpload(any(), eq("image/jpeg"), eq(FeedImage.DEFAULT_BLUR_LEVEL));
  }
}
