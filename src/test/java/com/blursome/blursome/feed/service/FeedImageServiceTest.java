package com.blursome.blursome.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeedImageServiceTest {

  private static final long MEMBER_ID = 100L;
  private static final long FEED_ID = 7L;

  @Mock
  private FeedRepository feedRepository;

  @Mock
  private FeedImageRepository feedImageRepository;

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

  private static final String KEY_A = "originals/100/11111111-1111-1111-1111-111111111111.png";
  private static final String KEY_B = "originals/100/22222222-2222-2222-2222-222222222222.jpg";
  private static final String VARIANT_A = "variants/100/11111111-1111-1111-1111-111111111111.jpg";
  private static final String VARIANT_B = "variants/100/22222222-2222-2222-2222-222222222222.jpg";

  @Test
  @DisplayName("full-replace: 삭제 후 저장하고, variant_key를 계산해 displayOrder 순서로 정렬해 응답한다")
  void replaceImages_fullReplace() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(keyGenerator.isOwnedOriginalKey(eq(MEMBER_ID), anyString())).willReturn(true);
    given(keyGenerator.toVariantKey(KEY_A)).willReturn(VARIANT_A);
    given(keyGenerator.toVariantKey(KEY_B)).willReturn(VARIANT_B);
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));
    given(feedImageRepository.saveAll(any()))
        .willAnswer(invocation -> invocation.<List<FeedImage>>getArgument(0));

    // 요청을 displayOrder 역순(2 → 1)으로 보내 응답 정렬을 검증한다.
    FeedImageSaveRequest request = new FeedImageSaveRequest(List.of(
        new FeedImageSaveRequest.Image(KEY_B, 2, null),
        new FeedImageSaveRequest.Image(KEY_A, 1, 70)));

    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request);

    // full-replace: 삭제(+flush)가 저장보다 먼저 일어나야 유니크 제약과 충돌하지 않는다.
    InOrder inOrder = Mockito.inOrder(feedImageRepository);
    inOrder.verify(feedImageRepository).deleteByFeedId(FEED_ID);
    inOrder.verify(feedImageRepository).flush();
    inOrder.verify(feedImageRepository).saveAll(any());

    assertThat(response.images()).hasSize(2);
    // 요청은 2→1 순서였지만 응답은 displayOrder 오름차순으로 정렬된다.
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::displayOrder)
        .containsExactly(1, 2);
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::variantUrl)
        .containsExactly("https://cdn/" + VARIANT_A, "https://cdn/" + VARIANT_B);
    // blurLevel 생략 시 기본값(80), 저장 직후 상태는 PROCESSING.
    assertThat(response.images().get(0).blurLevel()).isEqualTo(70);
    assertThat(response.images().get(1).blurLevel()).isEqualTo(FeedImage.DEFAULT_BLUR_LEVEL);
    assertThat(response.images())
        .allMatch(image -> image.processingStatus() == FeedImageProcessingStatus.PROCESSING);
  }

  @Test
  @DisplayName("순서가 중복되면 FEED_IMAGE_400_DUPLICATE_ORDER로 거부하고 저장하지 않는다")
  void replaceImages_rejectsDuplicateOrder() {
    Feed feed = mock(Feed.class);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));

    FeedImageSaveRequest request = new FeedImageSaveRequest(List.of(
        new FeedImageSaveRequest.Image(KEY_A, 1, 70),
        new FeedImageSaveRequest.Image(KEY_B, 1, 80)));

    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_DUPLICATE_ORDER");
    verify(feedImageRepository, never()).deleteByFeedId(any());
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("다른 회원 prefix·형식 위반 key는 FEED_IMAGE_400_INVALID_OBJECT_KEY로 거부하고 저장하지 않는다")
  void replaceImages_rejectsForeignObjectKey() {
    Feed feed = mock(Feed.class);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(keyGenerator.isOwnedOriginalKey(eq(MEMBER_ID), anyString())).willReturn(false);

    FeedImageSaveRequest request = new FeedImageSaveRequest(List.of(
        new FeedImageSaveRequest.Image(
            "originals/999/33333333-3333-3333-3333-333333333333.png", 1, 70)));

    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_INVALID_OBJECT_KEY");
    verify(feedImageRepository, never()).deleteByFeedId(any());
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("피드가 없으면 FEED_404_NOT_FOUND로 거부한다")
  void replaceImages_rejectsWhenFeedNotFound() {
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

    FeedImageSaveRequest request = new FeedImageSaveRequest(List.of(
        new FeedImageSaveRequest.Image("originals/100/a.png", 1, 70)));

    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
    verify(feedImageRepository, never()).saveAll(any());
  }
}
