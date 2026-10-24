package com.blursome.blursome.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse.Role;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.OriginalObject;
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
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
  private static final String KEY_C = "originals/100/33333333-3333-3333-3333-333333333333.png";
  private static final String KEY_A_REPROCESSED =
      "originals/100/44444444-4444-4444-4444-444444444444.png";
  private static final String VARIANT_A = "variants/100/11111111-1111-1111-1111-111111111111.jpg";
  private static final String VARIANT_B = "variants/100/22222222-2222-2222-2222-222222222222.jpg";
  private static final String VARIANT_C = "variants/100/33333333-3333-3333-3333-333333333333.jpg";
  private static final String VARIANT_A_REPROCESSED =
      "variants/100/44444444-4444-4444-4444-444444444444.jpg";

  @Test
  @DisplayName("새 objectKey만 보내면 PROCESSING 행을 만들고 displayOrder 순서로 응답한다")
  void replaceImages_whenUploadsOnly_createsProcessingImagesSortedByOrder() {
    // given
    givenLockedFeed();
    givenCurrentImages();
    given(keyGenerator.isOwnedOriginalKey(eq(MEMBER_ID), anyString())).willReturn(true);
    given(keyGenerator.toVariantKey(KEY_A)).willReturn(VARIANT_A);
    given(keyGenerator.toVariantKey(KEY_B)).willReturn(VARIANT_B);
    givenUploadedOriginal(KEY_A, "70");
    givenUploadedOriginal(KEY_B, "80");
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // 요청을 displayOrder 역순(2 → 1)으로 보내 응답 정렬을 검증한다.
    FeedImageSaveRequest request = request(
        FeedImageSaveRequest.Image.upload(KEY_B, 2, null),
        FeedImageSaveRequest.Image.upload(KEY_A, 1, 70));

    // when
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request);

    // then
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::displayOrder)
        .containsExactly(1, 2);
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::variantUrl)
        .containsExactly("https://cdn/" + VARIANT_A, "https://cdn/" + VARIANT_B);
    // blurLevel 생략 시 기본값(80), 저장 직후 상태는 PROCESSING.
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::blurLevel)
        .containsExactly(70, FeedImage.DEFAULT_BLUR_LEVEL);
    assertThat(response.images())
        .allMatch(image -> image.processingStatus() == FeedImageProcessingStatus.PROCESSING);
    verify(feedImageRepository, never()).deleteAll(any());
  }

  @Test
  @DisplayName("기존 사진만 재정렬하면 ID·key·상태를 유지하고 S3를 호출하지 않는다")
  void replaceImages_whenOnlyReordered_keepsImagesWithoutS3() {
    // given
    givenLockedFeed();
    FeedImage first = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    FeedImage second = currentImage(12L, 2, KEY_B, VARIANT_B, 80, FeedImageProcessingStatus.READY);
    givenCurrentImages(first, second);
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when: 1↔2 교환
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(12L, 1, null),
        FeedImageSaveRequest.Image.existing(10L, 2, null)));

    // then
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::id, FeedImageResponse.Image::displayOrder,
            FeedImageResponse.Image::variantUrl, FeedImageResponse.Image::processingStatus)
        .containsExactly(
            tuple(12L, 1, "https://cdn/" + VARIANT_B, FeedImageProcessingStatus.READY),
            tuple(10L, 2, "https://cdn/" + VARIANT_A, FeedImageProcessingStatus.READY));
    assertThat(first.getOriginalKey()).isEqualTo(KEY_A);
    // 교환 중 유니크 제약 충돌을 피하려고 임시 순서 반영(flush) 후 최종 순서를 반영(flush)한다.
    verify(feedImageRepository, times(2)).flush();
    verify(feedImageRepository, never()).deleteAll(any());
    verify(storageService, never()).reprocessOriginal(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("요청에서 빠진 기존 사진만 삭제한다")
  void replaceImages_whenImageOmitted_deletesOnlyOmittedImage() {
    // given
    givenLockedFeed();
    FeedImage kept = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    FeedImage omitted = currentImage(12L, 2, KEY_B, VARIANT_B, 80, FeedImageProcessingStatus.READY);
    givenCurrentImages(kept, omitted);
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, null)));

    // then
    verify(feedImageRepository).deleteAll(List.of(omitted));
    assertThat(response.images()).extracting(FeedImageResponse.Image::id).containsExactly(10L);
  }

  @Test
  @DisplayName("기존 사진 일부를 빼고 새 objectKey를 넣으면 삭제 후 새 행을 같은 순서에 만든다")
  void replaceImages_whenReplacingImage_deletesOldThenCreatesNew() {
    // given
    givenLockedFeed();
    FeedImage kept = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    FeedImage replaced = currentImage(12L, 2, KEY_B, VARIANT_B, 80, FeedImageProcessingStatus.READY);
    givenCurrentImages(kept, replaced);
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_C)).willReturn(true);
    given(keyGenerator.toVariantKey(KEY_C)).willReturn(VARIANT_C);
    givenUploadedOriginal(KEY_C, "90");
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, null),
        FeedImageSaveRequest.Image.upload(KEY_C, 2, 90)));

    // then: 비워진 순서를 새 행이 받으므로 삭제가 저장보다 먼저 반영돼야 한다.
    InOrder inOrder = Mockito.inOrder(feedImageRepository);
    inOrder.verify(feedImageRepository).deleteAll(List.of(replaced));
    inOrder.verify(feedImageRepository, times(2)).flush();
    inOrder.verify(feedImageRepository).saveAll(any());
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::displayOrder, FeedImageResponse.Image::variantUrl,
            FeedImageResponse.Image::processingStatus)
        .containsExactly(
            tuple(1, "https://cdn/" + VARIANT_A, FeedImageProcessingStatus.READY),
            tuple(2, "https://cdn/" + VARIANT_C, FeedImageProcessingStatus.PROCESSING));
  }

  @Test
  @DisplayName("다른 피드의 사진 id를 보내면 FEED_IMAGE_404_NOT_FOUND로 거부하고 아무것도 바꾸지 않는다")
  void replaceImages_whenForeignImageId_throwsNotFound() {
    // given
    givenLockedFeed();
    givenCurrentImages(currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(999L, 1, null))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_404_NOT_FOUND");
    verify(feedImageRepository, never()).deleteAll(any());
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("blurLevel이 현재 값과 같거나 생략되면 재처리하지 않는다")
  void replaceImages_whenBlurLevelUnchanged_doesNotReprocess() {
    // given
    givenLockedFeed();
    FeedImage same = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    FeedImage omitted = currentImage(12L, 2, KEY_B, VARIANT_B, 70, FeedImageProcessingStatus.READY);
    givenCurrentImages(same, omitted);
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when
    feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, 80),
        FeedImageSaveRequest.Image.existing(12L, 2, null)));

    // then
    verify(storageService, never()).reprocessOriginal(anyString(), anyString(), anyInt());
    assertThat(omitted.getBlurLevel()).isEqualTo(70);
    assertThat(same.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
  }

  @Test
  @DisplayName("blurLevel이 바뀌면 원본을 새 key로 복사하고 같은 id를 새 key·PROCESSING으로 전환한다")
  void replaceImages_whenBlurLevelChanged_reprocessesWithNewKeys() {
    // given
    givenLockedFeed();
    FeedImage image = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    givenCurrentImages(image);
    given(keyGenerator.generateReprocessedOriginalKey(MEMBER_ID, KEY_A))
        .willReturn(KEY_A_REPROCESSED);
    given(keyGenerator.toVariantKey(KEY_A_REPROCESSED)).willReturn(VARIANT_A_REPROCESSED);
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, 90)));

    // then
    verify(storageService).reprocessOriginal(KEY_A, KEY_A_REPROCESSED, 90);
    assertThat(image.getOriginalKey()).isEqualTo(KEY_A_REPROCESSED);
    assertThat(image.getVariantKey()).isEqualTo(VARIANT_A_REPROCESSED);
    assertThat(response.images())
        .extracting(FeedImageResponse.Image::id, FeedImageResponse.Image::blurLevel,
            FeedImageResponse.Image::variantUrl, FeedImageResponse.Image::processingStatus)
        .containsExactly(tuple(10L, 90, "https://cdn/" + VARIANT_A_REPROCESSED,
            FeedImageProcessingStatus.PROCESSING));
  }

  @Test
  @DisplayName("S3 복사가 실패하면 5xx로 거부하고 기존 행의 key·상태와 삭제 대상을 그대로 둔다")
  void replaceImages_whenCopyFails_leavesImagesUnchanged() {
    // given
    givenLockedFeed();
    FeedImage image = currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY);
    FeedImage omitted = currentImage(12L, 2, KEY_B, VARIANT_B, 80, FeedImageProcessingStatus.READY);
    givenCurrentImages(image, omitted);
    given(keyGenerator.generateReprocessedOriginalKey(MEMBER_ID, KEY_A))
        .willReturn(KEY_A_REPROCESSED);
    willThrow(S3Exception.builder().statusCode(500).build())
        .given(storageService).reprocessOriginal(KEY_A, KEY_A_REPROCESSED, 90);

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, 90))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "SERVER_ERROR_500_INTERNAL_SERVER_ERROR");
    assertThat(image.getOriginalKey()).isEqualTo(KEY_A);
    assertThat(image.getVariantKey()).isEqualTo(VARIANT_A);
    assertThat(image.getBlurLevel()).isEqualTo(80);
    assertThat(image.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    verify(feedImageRepository, never()).deleteAll(any());
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("id와 objectKey가 모두 없거나 모두 있으면 FEED_IMAGE_400_INVALID_REFERENCE로 거부한다")
  void replaceImages_whenReferenceIsNotExclusive_throwsInvalidReference() {
    // given
    givenLockedFeed();
    FeedImageSaveRequest neither = request(new FeedImageSaveRequest.Image(null, null, 1, 80));
    FeedImageSaveRequest both = request(new FeedImageSaveRequest.Image(10L, KEY_A, 1, 80));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, neither))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_INVALID_REFERENCE");
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, both))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_INVALID_REFERENCE");
    verify(feedImageRepository, never()).findByFeedIdOrderByDisplayOrderAsc(any());
  }

  @Test
  @DisplayName("같은 id나 같은 objectKey를 두 번 쓰면 FEED_IMAGE_400_DUPLICATE_REFERENCE로 거부한다")
  void replaceImages_whenSameReferenceRepeated_throwsDuplicateReference() {
    // given
    givenLockedFeed();
    FeedImageSaveRequest sameId = request(
        FeedImageSaveRequest.Image.existing(10L, 1, null),
        FeedImageSaveRequest.Image.existing(10L, 2, null));
    FeedImageSaveRequest sameKey = request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, null),
        FeedImageSaveRequest.Image.upload(KEY_A, 2, null));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, sameId))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_DUPLICATE_REFERENCE");
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, sameKey))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_DUPLICATE_REFERENCE");
  }

  @Test
  @DisplayName("유지하는 기존 사진의 원본 key를 새 사진으로 또 보내면 FEED_IMAGE_400_DUPLICATE_REFERENCE로 거부한다")
  void replaceImages_whenUploadReusesKeptKey_throwsDuplicateReference() {
    // given
    givenLockedFeed();
    givenCurrentImages(currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY));
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, null),
        FeedImageSaveRequest.Image.upload(KEY_A, 2, null))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_DUPLICATE_REFERENCE");
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("순서가 중복되면 FEED_IMAGE_400_DUPLICATE_ORDER로 거부하고 저장하지 않는다")
  void replaceImages_whenDuplicateOrder_throwsDuplicateOrder() {
    // given
    givenLockedFeed();
    FeedImageSaveRequest request = request(
        FeedImageSaveRequest.Image.existing(10L, 1, null),
        FeedImageSaveRequest.Image.upload(KEY_B, 1, 80));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_DUPLICATE_ORDER");
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("다른 회원 prefix·형식 위반 key는 FEED_IMAGE_400_INVALID_OBJECT_KEY로 거부하고 저장하지 않는다")
  void replaceImages_whenForeignObjectKey_throwsInvalidObjectKey() {
    // given
    givenLockedFeed();
    given(keyGenerator.isOwnedOriginalKey(eq(MEMBER_ID), anyString())).willReturn(false);
    FeedImageSaveRequest request = request(FeedImageSaveRequest.Image.upload(
        "originals/999/33333333-3333-3333-3333-333333333333.png", 1, 70));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_INVALID_OBJECT_KEY");
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("새 사진의 요청 blurLevel이 업로드된 원본 메타데이터와 다르면 FEED_IMAGE_400_BLUR_LEVEL_MISMATCH로 거부한다")
  void replaceImages_whenUploadBlurLevelDiffersFromMetadata_throwsMismatch() {
    // given: Presigned 발급·업로드는 50으로 했는데 저장 요청은 100
    givenLockedFeed();
    givenCurrentImages();
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);
    givenUploadedOriginal(KEY_A, "50");

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, 100))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_BLUR_LEVEL_MISMATCH");
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("요청에서 뺀 기존 사진의 key를 새 사진으로 다시 보내며 blurLevel을 바꾸면 메타데이터 불일치로 거부한다")
  void replaceImages_whenDroppedKeyResentWithOtherBlurLevel_throwsMismatch() {
    // given: id=10(KEY_A, blur 80)을 목록에서 빼고 같은 key를 objectKey로 95와 함께 보낸다.
    givenLockedFeed();
    givenCurrentImages(currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY));
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);
    givenUploadedOriginal(KEY_A, "80");

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, 95))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_BLUR_LEVEL_MISMATCH");
    verify(feedImageRepository, never()).deleteAll(any());
    verify(storageService, never()).reprocessOriginal(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("새 사진의 blurLevel을 생략하면 업로드된 원본 메타데이터 값으로 기록한다")
  void replaceImages_whenUploadBlurLevelOmitted_usesMetadataValue() {
    // given
    givenLockedFeed();
    givenCurrentImages();
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);
    given(keyGenerator.toVariantKey(KEY_A)).willReturn(VARIANT_A);
    givenUploadedOriginal(KEY_A, "60");
    givenSaveAllReturnsArgument();
    givenVariantUrls();

    // when
    FeedImageResponse response = feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, null)));

    // then
    assertThat(response.images()).extracting(FeedImageResponse.Image::blurLevel).containsExactly(60);
  }

  @Test
  @DisplayName("원본에 blur-level 메타데이터가 없으면 Lambda와 같이 기본값 80을 적용 값으로 본다")
  void replaceImages_whenMetadataMissing_usesLambdaDefault() {
    // given
    givenLockedFeed();
    givenCurrentImages();
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);
    givenUploadedOriginal(KEY_A, null);

    // when & then: Lambda는 80으로 블러하므로 70 요청은 불일치다.
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, 70))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_BLUR_LEVEL_MISMATCH");
  }

  @Test
  @DisplayName("업로드되지 않은 objectKey는 FEED_IMAGE_400_ORIGINAL_NOT_UPLOADED로 거부한다")
  void replaceImages_whenOriginalNotUploaded_throwsOriginalNotUploaded() {
    // given
    givenLockedFeed();
    givenCurrentImages();
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_A)).willReturn(true);
    given(storageService.findOriginal(KEY_A)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.upload(KEY_A, 1, null))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_ORIGINAL_NOT_UPLOADED");
    verify(feedImageRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("새 사진 원본 조회가 S3 오류로 실패하면 5xx로 거부하고 재처리 복사도 하지 않는다")
  void replaceImages_whenOriginalLookupFails_throwsServerError() {
    // given: 기존 사진 재블러와 새 사진이 함께 있어도 원본 확인이 먼저라 복사가 일어나지 않는다.
    givenLockedFeed();
    givenCurrentImages(currentImage(10L, 1, KEY_A, VARIANT_A, 80, FeedImageProcessingStatus.READY));
    given(keyGenerator.isOwnedOriginalKey(MEMBER_ID, KEY_B)).willReturn(true);
    given(storageService.findOriginal(KEY_B))
        .willThrow((S3Exception) S3Exception.builder().statusCode(403).build());

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request(
        FeedImageSaveRequest.Image.existing(10L, 1, 95),
        FeedImageSaveRequest.Image.upload(KEY_B, 2, null))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "SERVER_ERROR_500_INTERNAL_SERVER_ERROR");
    verify(storageService, never()).reprocessOriginal(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("피드가 없으면 FEED_404_NOT_FOUND로 거부한다")
  void replaceImages_whenFeedNotFound_throwsFeedNotFound() {
    // given
    given(feedRepository.findByMemberIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());
    FeedImageSaveRequest request = request(FeedImageSaveRequest.Image.upload(KEY_A, 1, 70));

    // when & then
    assertThatThrownBy(() -> feedImageService.replaceImages(MEMBER_ID, request))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
    verify(feedImageRepository, never()).saveAll(any());
  }

  private void givenLockedFeed() {
    Feed feed = mock(Feed.class);
    lenient().when(feed.getId()).thenReturn(FEED_ID);
    given(feedRepository.findByMemberIdForUpdate(MEMBER_ID)).willReturn(Optional.of(feed));
  }

  private void givenUploadedOriginal(String objectKey, String blurLevelMetadata) {
    given(storageService.findOriginal(objectKey))
        .willReturn(Optional.of(new OriginalObject(blurLevelMetadata)));
  }

  private void givenCurrentImages(FeedImage... images) {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(List.of(images));
  }

  private void givenSaveAllReturnsArgument() {
    given(feedImageRepository.saveAll(any()))
        .willAnswer(invocation -> invocation.<List<FeedImage>>getArgument(0));
  }

  private void givenVariantUrls() {
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));
  }

  private static FeedImageSaveRequest request(FeedImageSaveRequest.Image... images) {
    return new FeedImageSaveRequest(List.of(images));
  }

  /** 현재 피드에 저장돼 있는 사진(id 부여)을 만든다. */
  private static FeedImage currentImage(Long id, int displayOrder, String originalKey,
      String variantKey, int blurLevel, FeedImageProcessingStatus status) {
    FeedImage image = FeedImage.create(
        mock(Feed.class), displayOrder, originalKey, variantKey, blurLevel);
    ReflectionTestUtils.setField(image, "id", id);
    if (status == FeedImageProcessingStatus.READY) {
      image.markReady();
    } else if (status == FeedImageProcessingStatus.FAILED) {
      image.markFailed();
    }
    return image;
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

  /** 1~count번 순서를 가진 READY 이미지 목록. variant_key는 순서로 결정한다. 공개 게이트(정확히 5장) 검증용. */
  private static List<FeedImage> readyImages(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(order -> feedImage(order, "variants/100/" + order + ".jpg",
            FeedImageProcessingStatus.READY))
        .toList();
  }

  @Test
  @DisplayName("공개 조회: 정확히 5장 전부 READY면 displayOrder 순서로 블러본 URL을 노출한다")
  void getPublicFeedImages_exactlyFiveReady() {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(readyImages(5));
    given(storageService.toPublicVariantUrl(anyString()))
        .willAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

    var response = feedImageService.getPublicFeedImages(FEED_ID);

    assertThat(response.images())
        .extracting(image -> image.displayOrder())
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(response.images())
        .extracting(image -> image.variantUrl())
        .containsExactly("https://cdn/variants/100/1.jpg", "https://cdn/variants/100/2.jpg",
            "https://cdn/variants/100/3.jpg", "https://cdn/variants/100/4.jpg",
            "https://cdn/variants/100/5.jpg");
  }

  @Test
  @DisplayName("공개 조회: 5장이라도 하나가 READY가 아니면 FEED_404_NOT_FOUND로 비노출한다")
  void getPublicFeedImages_notAllReady() {
    List<FeedImage> images = new java.util.ArrayList<>(readyImages(4));
    images.add(feedImage(5, VARIANT_A, FeedImageProcessingStatus.PROCESSING));
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(images);

    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(FEED_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  @Test
  @DisplayName("공개 조회: 전부 READY라도 5장 미만이면 FEED_404_NOT_FOUND로 비노출한다")
  void getPublicFeedImages_fewerThanFive() {
    given(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(FEED_ID)).willReturn(readyImages(4));

    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(FEED_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  @Test
  @DisplayName("공개 조회: 이미지가 0장이면 FEED_404_NOT_FOUND로 비노출한다")
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

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 2, Role.PARTNER);

    assertThat(response.images())
        .extracting(image -> image.displayOrder())
        .containsExactly(1, 2, 3);
    assertThat(response.images())
        .extracting(image -> image.revealed())
        .containsExactly(true, true, false);
    // 전달한 role(PARTNER)이 모든 사진에 그대로 표기된다.
    assertThat(response.images())
        .extracting(image -> image.role())
        .containsOnly(Role.PARTNER);
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
    var response = feedImageService.issueRevealedImages(MEMBER_ID, 5, Role.ME);

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

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 0, Role.ME);

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

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 1, Role.PARTNER);

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

    var response = feedImageService.issueRevealedImages(MEMBER_ID, 3, Role.PARTNER);

    assertThat(response.images()).isEmpty();
    verify(storageService, never()).presignOriginalDownload(anyString());
  }
}
