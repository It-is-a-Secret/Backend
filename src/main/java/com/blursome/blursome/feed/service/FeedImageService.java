package com.blursome.blursome.feed.service;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.MyFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.dto.response.PublicFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse.PresignedUrl;
import com.blursome.blursome.feed.exception.FeedErrorCode;
import com.blursome.blursome.feed.exception.FeedImageErrorCode;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피드 이미지 도메인 서비스. 현재는 원본 업로드용 Presigned PUT URL 발급을 담당한다.
 *
 * <p>발급은 DB를 거치지 않는다. 회원 id로 결정론적 원본 key를 만들고({@link S3ObjectKeyGenerator}),
 * blur_level을 메타데이터로 박은 Presigned PUT을 발급한다({@link S3StorageService}). 실제 {@link FeedImage}
 * 행은 프론트가 S3 업로드를 마친 뒤 {@link #replaceImages} 메타데이터 저장 API에서 생성된다.
 */
@Service
@RequiredArgsConstructor
public class FeedImageService {

  private final FeedRepository feedRepository;
  private final FeedImageRepository feedImageRepository;
  private final S3ObjectKeyGenerator keyGenerator;
  private final S3StorageService storageService;

  /**
   * 요청한 사진들에 대해 originals 버킷 Presigned PUT URL을 사진 순서대로 발급한다.
   *
   * @param memberId 업로드 주체(인증된 회원). 원본 key prefix에 사용된다.
   * @param request 사진별 파일명·콘텐츠 타입·블러 강도
   * @return 사진별 {@code uploadUrl}·{@code originalKey}·{@code requiredHeaders}
   */
  public PresignedUrlResponse issuePresignedUploadUrls(Long memberId, PresignedUrlRequest request) {
    List<PresignedUrl> images = request.images().stream()
        .map(image -> issueOne(memberId, image))
        .toList();
    return new PresignedUrlResponse(images);
  }

  private PresignedUrl issueOne(Long memberId, ImageRequest image) {
    int blurLevel = image.blurLevel() != null ? image.blurLevel() : FeedImage.DEFAULT_BLUR_LEVEL;
    String originalKey = keyGenerator.generateOriginalKey(memberId, extractExtension(image.fileName()));
    PresignedUpload upload =
        storageService.presignOriginalUpload(originalKey, image.contentType(), blurLevel);
    return new PresignedUrl(upload.uploadUrl(), originalKey, upload.requiredHeaders());
  }

  /**
   * 회원 피드의 이미지를 요청 목록으로 <b>전체 대체(full-replace)</b>한다. 요청 목록을 피드 사진의 전체 집합으로
   * 보고 기존 DB 행을 삭제한 뒤 새로 저장하므로, 추가·삭제·재정렬이 한 요청으로 처리된다.
   *
   * <p>각 사진의 {@code variantKey}는 {@code objectKey}(원본 key)에서 결정론적으로 계산하고, 상태는
   * {@link com.blursome.blursome.feed.domain.FeedImageProcessingStatus#PROCESSING}으로 시작한다. 블러본은
   * Lambda가 비동기로 생성하므로 저장 시점에는 아직 존재하지 않을 수 있다.
   *
   * <p>full-replace는 <b>DB 행만 대체</b>하며 기존 S3 객체를 즉시 삭제하지 않는다. 제거된 객체는 고아로 두고
   * 후속 cleanup으로 정리한다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-2·§8-3)
   *
   * @param memberId 인증된 회원 id. 본인 피드(회원:피드 1:1)를 도출하므로 별도 소유권 검증이 불필요하다.
   * @param request 저장할 사진 목록(원본 key·순서·블러 강도). 장수(1~5)·순서/블러 범위는 DTO에서 검증된다.
   * @return 저장된 사진을 노출 순서대로 담은 응답
   * @throws BaseException 피드가 없거나({@code FEED_404_NOT_FOUND}) 순서가 중복된 경우
   *     ({@code FEED_IMAGE_400_DUPLICATE_ORDER})
   */
  @Transactional
  public FeedImageResponse replaceImages(Long memberId, FeedImageSaveRequest request) {
    Feed feed = feedRepository.findByMemberId(memberId)
        .orElseThrow(() -> BaseException.from(FeedErrorCode.FEED_NOT_FOUND));
    validateNoDuplicateOrder(request.images());
    validateOwnedObjectKeys(memberId, request.images());

    // 기존 행을 먼저 DB에 반영(flush)해야, 같은 트랜잭션에서 동일 displayOrder를 다시 저장할 때
    // uk_feed_image_order 유니크 제약과 충돌하지 않는다.
    feedImageRepository.deleteByFeedId(feed.getId());
    feedImageRepository.flush();

    List<FeedImage> saved = feedImageRepository.saveAll(
        request.images().stream()
            .map(image -> FeedImage.create(
                feed,
                image.displayOrder(),
                image.objectKey(),
                keyGenerator.toVariantKey(image.objectKey()),
                image.blurLevel()))
            .toList());

    // 응답은 노출 순서(displayOrder)대로 내려준다. saveAll 반환 순서는 요청 순서라 정렬을 보장하지 않는다.
    return new FeedImageResponse(saved.stream()
        .sorted(Comparator.comparing(FeedImage::getDisplayOrder))
        .map(image -> FeedImageResponse.Image.of(image,
            storageService.toPublicVariantUrl(image.getVariantKey())))
        .toList());
  }

  /**
   * 공개 피드 이미지 조회(다른 회원이 보는 피드). 현재 DB 목록의 <b>모든 이미지가 {@code READY}일 때만</b>
   * {@code displayOrder} 순서로 노출한다(전부-또는-비노출). 하나라도 {@code PROCESSING}/{@code FAILED}이거나
   * 이미지가 0장(미존재 피드 포함)이면, 깨진/불완전한 블러본 노출을 막기 위해 노출 대상에서 제외하고
   * {@code FEED_404_NOT_FOUND}로 응답한다.
   *
   * <p>이미지 0장 피드를 명시적으로 제외하는 이유: "전부 {@code READY}" 게이트는 빈 컬렉션에 대해 vacuously
   * true가 되어 이미지 없는 피드가 새는 함정이 있으므로, {@code isEmpty()} 가드를 별도로 둔다.
   * (설계: {@code FEED_IMAGE_DOMAIN.md} §2-5, 이슈 #52 0장 처리)
   *
   * @param feedId 조회 대상 피드 id
   * @return 모든 이미지가 {@code READY}인 경우 노출 순서대로 담은 공개 응답
   * @throws BaseException 게이트 미통과 시 {@code FEED_404_NOT_FOUND}
   */
  @Transactional(readOnly = true)
  public PublicFeedImagesResponse getPublicFeedImages(Long feedId) {
    List<FeedImage> images = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    if (images.isEmpty() || images.stream().anyMatch(image -> !image.isReady())) {
      throw BaseException.from(FeedErrorCode.FEED_NOT_FOUND);
    }
    return new PublicFeedImagesResponse(images.stream()
        .map(image -> PublicFeedImagesResponse.Image.of(image,
            storageService.toPublicVariantUrl(image.getVariantKey())))
        .toList());
  }

  /**
   * 본인 피드 이미지 관리 조회. 공개 조회와 달리 업로드 진행/실패를 보여주기 위해 {@code PROCESSING}·
   * {@code FAILED} 상태도 함께 {@code displayOrder} 순서로 내려준다. {@code FAILED}가 하나라도 있으면
   * {@code reuploadRequired=true}로 해당 사진 재업로드를 안내한다(v1은 자동 재시도·Lambda 재트리거 없음).
   *
   * @param memberId 인증된 회원 id. 본인 피드(회원:피드 1:1)를 도출하므로 별도 소유권 검증이 불필요하다.
   * @return 본인 피드 사진을 노출 순서대로 담고 재업로드 필요 여부를 함께 내린 응답
   * @throws BaseException 피드가 없는 경우 {@code FEED_404_NOT_FOUND}
   */
  @Transactional(readOnly = true)
  public MyFeedImagesResponse getMyFeedImages(Long memberId) {
    Feed feed = feedRepository.findByMemberId(memberId)
        .orElseThrow(() -> BaseException.from(FeedErrorCode.FEED_NOT_FOUND));
    List<FeedImage> images = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feed.getId());
    boolean reuploadRequired = images.stream()
        .anyMatch(image -> image.getProcessingStatus() == FeedImageProcessingStatus.FAILED);
    return new MyFeedImagesResponse(reuploadRequired, images.stream()
        .map(image -> FeedImageResponse.Image.of(image,
            storageService.toPublicVariantUrl(image.getVariantKey())))
        .toList());
  }

  /** 한 요청 내 {@code displayOrder} 중복을 검증한다. DTO로 표현하기 어려운 도메인 규칙이라 서비스에서 다룬다. */
  private void validateNoDuplicateOrder(List<FeedImageSaveRequest.Image> images) {
    long distinctOrders = images.stream()
        .map(FeedImageSaveRequest.Image::displayOrder)
        .distinct()
        .count();
    if (distinctOrders != images.size()) {
      throw BaseException.from(FeedImageErrorCode.DUPLICATE_ORDER);
    }
  }

  /**
   * 클라이언트가 보낸 원본 key가 인증 회원 소유의 정상 형식인지 검증한다. 다른 회원 prefix나 형식 위반 key를
   * 그대로 저장하면 IDOR·깨진 key 문제가 되고, {@code toVariantKey} 계산이 깨져 500으로 이어진다.
   */
  private void validateOwnedObjectKeys(Long memberId, List<FeedImageSaveRequest.Image> images) {
    boolean allOwned = images.stream()
        .allMatch(image -> keyGenerator.isOwnedOriginalKey(memberId, image.objectKey()));
    if (!allOwned) {
      throw BaseException.from(FeedImageErrorCode.INVALID_OBJECT_KEY);
    }
  }

  /** 파일명에서 확장자(점 제외)를 추출한다. DTO 검증이 확장자 존재를 보장하지만 마지막 방어선으로 한 번 더 확인한다. */
  private String extractExtension(String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    if (lastDot < 0 || lastDot == fileName.length() - 1) {
      throw new IllegalArgumentException("파일명에 확장자가 없습니다: " + fileName);
    }
    return fileName.substring(lastDot + 1);
  }
}
