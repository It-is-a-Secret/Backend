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

  /** 주어진 순서·variant_key·상태를 가진 피드 이미지를 만든다. 조회 테스트의 게이트·정렬 검증용. */
  private static FeedImage feedImage(int displayOrder, String variantKey,
      FeedImageProcessingStatus status) {
    FeedImage image = FeedImage.create(
        mock(Feed.class), displayOrder, "originals/100/" + displayOrder + ".png", variantKey, 80);
    if (status == FeedImageProcessingStatus.READY) {
      image.markReady();
    } else if (status == FeedImageProcessingStatus.FAILED) {
      image.markFailed();
    }
    return image;
  }

  @Test
  @DisplayName("공개 조회: 모든 이미지가 READY면 displayOrder 순서로 블러본 URL을 노출한다")
  void getPublicFeedImages_allReady() {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        feedImage(1, VARIANT_A, FeedImageProcessingStatus.READY),
        feedImage(2, VARIANT_B, FeedImageProcessingStatus.READY)));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.getPublicFeedImages(FEED_ID);

    assertThat(response.images())
        .extracting(image -> image.displayOrder())
        .containsExactly(1, 2);
    assertThat(response.images())
        .extracting(image -> image.variantUrl())
        .containsExactly("https://cdn/" + VARIANT_A, "https://cdn/" + VARIANT_B);
  }

  @Test
  @DisplayName("공개 조회: 하나라도 READY가 아니면 FEED_404_NOT_FOUND로 비노출한다")
  void getPublicFeedImages_notAllReady() {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        feedImage(1, VARIANT_A, FeedImageProcessingStatus.READY),
        feedImage(2, VARIANT_B, FeedImageProcessingStatus.PROCESSING)));

    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(FEED_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  @Test
  @DisplayName("공개 조회: 이미지가 0장이면(빈 컬렉션 vacuous-true 함정) FEED_404_NOT_FOUND로 비노출한다")
  void getPublicFeedImages_empty() {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of());

    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(FEED_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  @Test
  @DisplayName("본인 조회: PROCESSING/FAILED를 포함해 내려주고 FAILED가 있으면 reuploadRequired=true")
  void getMyFeedImages_includesFailedAndFlagsReupload() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        feedImage(1, VARIANT_A, FeedImageProcessingStatus.READY),
        feedImage(2, VARIANT_B, FeedImageProcessingStatus.FAILED)));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.getMyFeedImages(MEMBER_ID);

    assertThat(response.reuploadRequired()).isTrue();
    assertThat(response.images())
        .extracting(image -> image.processingStatus())
        .containsExactly(FeedImageProcessingStatus.READY, FeedImageProcessingStatus.FAILED);
  }

  @Test
  @DisplayName("본인 조회: FAILED가 없으면 reuploadRequired=false")
  void getMyFeedImages_noFailed() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        feedImage(1, VARIANT_A, FeedImageProcessingStatus.READY),
        feedImage(2, VARIANT_B, FeedImageProcessingStatus.PROCESSING)));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.getMyFeedImages(MEMBER_ID);

    assertThat(response.reuploadRequired()).isFalse();
    assertThat(response.images()).hasSize(2);
  }

  @Test
  @DisplayName("본인 조회: 피드가 없으면 FEED_404_NOT_FOUND로 거부한다")
  void getMyFeedImages_rejectsWhenFeedNotFound() {
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> feedImageService.getMyFeedImages(MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  // ---------- issueRevealedImages (채팅 단계 공개) ----------

  /** displayOrder/원본 key/variant key를 가진 피드 이미지를 지정 상태로 만든다(공개 발급 테스트용). */
  private static FeedImage revealImage(int displayOrder, FeedImageProcessingStatus status) {
    FeedImage image = FeedImage.create(mock(Feed.class), displayOrder,
        "originals/100/" + displayOrder + ".png", "variants/100/" + displayOrder + ".jpg", 80);
    if (status == FeedImageProcessingStatus.READY) {
      image.markReady();
    } else if (status == FeedImageProcessingStatus.FAILED) {
      image.markFailed();
    }
    return image;
  }

  @Test
  @DisplayName("공개 발급: 공개 장수 N 이하는 원본 Presigned GET, 나머지는 블러본 URL로 displayOrder 순서로 내려준다")
  void issueRevealedImages_revealsFirstNOriginals() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        revealImage(1, FeedImageProcessingStatus.READY),
        revealImage(2, FeedImageProcessingStatus.READY),
        revealImage(3, FeedImageProcessingStatus.READY)));
    given(storageService.presignOriginalDownload(anyString()))
        .willAnswer(invocation -> "https://original/" + invocation.getArgument(0));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 2);

    assertThat(response.images())
        .extracting(image -> image.displayOrder())
        .containsExactly(1, 2, 3);
    assertThat(response.images())
        .extracting(image -> image.revealed())
        .containsExactly(true, true, false);
    assertThat(response.images())
        .extracting(image -> image.imageUrl())
        .containsExactly(
            "https://original/originals/100/1.png",
            "https://original/originals/100/2.png",
            "https://cdn/variants/100/3.jpg");
    // 블러본 사진에는 원본 발급을 호출하지 않는다.
    verify(storageService, never()).presignOriginalDownload("originals/100/3.png");
  }

  @Test
  @DisplayName("공개 발급: 공개 장수가 보유 사진 수보다 크면 보유 수로 캡해 전부 공개한다")
  void issueRevealedImages_capsByOwnedCount() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        revealImage(1, FeedImageProcessingStatus.READY),
        revealImage(2, FeedImageProcessingStatus.READY),
        revealImage(3, FeedImageProcessingStatus.READY)));
    given(storageService.presignOriginalDownload(anyString()))
        .willAnswer(invocation -> "https://original/" + invocation.getArgument(0));

    // COMPLETED(5)인데 사진은 3장 → 3장 전부 공개.
    var response = feedImageService.issueRevealedImages(MEMBER_ID, 5);

    assertThat(response.images())
        .extracting(image -> image.revealed())
        .containsExactly(true, true, true);
    verify(storageService, never()).toPublicVariantUrl(anyString());
  }

  @Test
  @DisplayName("공개 발급: 공개 장수가 0이면(MATCHED) 전부 블러본으로 내려주고 원본을 발급하지 않는다")
  void issueRevealedImages_revealsNoneWhenZero() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        revealImage(1, FeedImageProcessingStatus.READY),
        revealImage(2, FeedImageProcessingStatus.READY)));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 0);

    assertThat(response.images())
        .extracting(image -> image.revealed())
        .containsExactly(false, false);
    verify(storageService, never()).presignOriginalDownload(anyString());
  }

  @Test
  @DisplayName("공개 발급: 미공개 사진의 블러본이 READY가 아니면(PROCESSING/FAILED) 깨진 URL 노출을 막기 위해 응답에서 제외한다")
  void issueRevealedImages_excludesUnrevealedNonReadyBlur() {
    Feed feed = mock(Feed.class);
    given(feed.getId()).willReturn(FEED_ID);
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(feed));
    // 1: 공개(원본) / 2: 미공개 READY(블러본 노출) / 3: 미공개 PROCESSING(제외) / 4: 미공개 FAILED(제외)
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(
        revealImage(1, FeedImageProcessingStatus.PROCESSING),
        revealImage(2, FeedImageProcessingStatus.READY),
        revealImage(3, FeedImageProcessingStatus.PROCESSING),
        revealImage(4, FeedImageProcessingStatus.FAILED)));
    given(storageService.presignOriginalDownload(anyString()))
        .willAnswer(invocation -> "https://original/" + invocation.getArgument(0));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 1);

    // 공개된 1번 원본은 블러본 상태(PROCESSING)와 무관하게 제공되고, 미공개 중 READY인 2번만 블러본으로 남는다.
    assertThat(response.images())
        .extracting(image -> image.displayOrder())
        .containsExactly(1, 2);
    assertThat(response.images())
        .extracting(image -> image.revealed())
        .containsExactly(true, false);
  }

  @Test
  @DisplayName("공개 발급: 대상 회원의 피드가 없으면 빈 목록을 돌려준다(예외 아님)")
  void issueRevealedImages_emptyWhenNoFeed() {
    given(feedRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 3);

    assertThat(response.images()).isEmpty();
    verify(storageService, never()).presignOriginalDownload(anyString());
  }
}
