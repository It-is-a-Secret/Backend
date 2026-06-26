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
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse.PresignedUrl;
import com.blursome.blursome.feed.exception.FeedErrorCode;
import com.blursome.blursome.feed.exception.FeedImageErrorCode;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.ArrayList;
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
   * 공개 피드 이미지 조회(다른 회원이 보는 피드). DB 목록이 <b>정확히
   * {@link FeedImage#REQUIRED_PUBLISH_COUNT}장이고 전부 {@code READY}일 때만</b>(전부-또는-비노출)
   * {@code displayOrder} 순서로 노출한다. 장수 미달/초과거나 하나라도 {@code PROCESSING}/{@code FAILED}이면,
   * 깨진/불완전한 블러본 노출을 막기 위해 노출 대상에서 제외하고 {@code FEED_404_NOT_FOUND}로 응답한다.
   *
   * <p>공개 가능 판정은 {@link FeedImage#isPublishable(List)} 단일 게이트를 따른다. 0장 피드도 장수 조건에서
   * 자연히 비노출되며("정확히 5장" ≠ 0), 탐색 후보 질의({@code DiscoveryRepository})와 같은 규칙이다.
   * (설계: {@code FEED_IMAGE_DOMAIN.md} §2-5, 이슈 #72 정확히-5장 게이트)
   *
   * @param feedId 조회 대상 피드 id
   * @return 정확히 5장 전부 {@code READY}인 경우 노출 순서대로 담은 공개 응답
   * @throws BaseException 게이트 미통과 시 {@code FEED_404_NOT_FOUND}
   */
  @Transactional(readOnly = true)
  public PublicFeedImagesResponse getPublicFeedImages(Long feedId) {
    List<FeedImage> images = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    if (!FeedImage.isPublishable(images)) {
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

  /**
   * 채팅 단계 공개용. 회원 피드 사진을 {@code displayOrder} 순서로 정렬해, 공개 장수 {@code revealCount} 이하의
   * 사진은 비공개 원본 단기 Presigned GET을, 나머지는 공개 블러본 URL을 담아 돌려준다. 채팅 단계가 오를수록
   * 원본을 1장씩 공개하는 규칙(설계 §3·§4, ④-b)을 feed 도메인 쪽에서 실현하는 진입점이다.
   *
   * <p>chat 도메인이 {@code ChatRoomProgressStatus.revealedOriginalCount()}로 산출한 공개 장수 N만 넘기면,
   * feed가 원본/블러본 key·버킷·발급을 전담한다(chat→feed 단방향, chat은 key 구조를 모름). 실제 공개 장수는
   * <b>보유 사진 수로 캡</b>한다({@code min(revealCount, 보유 사진 수)}). 예) 사진 3장 + COMPLETED(5) → 3장 전부 공개.
   *
   * <p>피드가 없거나 사진이 0장이면 빈 목록을 돌려준다. 원본 공개는 채팅방 참여자·단계 검증을 통과한 호출만
   * 도달하므로(검증 책임은 chat 서비스), 여기서는 별도 권한 검증을 하지 않는다.
   *
   * <p><b>블러본 미생성 처리</b>: 공개되는 원본은 variants(블러본) 생성 상태와 무관하게 originals 객체가 항상
   * 존재하므로 그대로 Presigned GET을 발급한다. 반면 아직 공개되지 않은 사진은 블러본으로 제공해야 하는데,
   * 블러본이 {@code READY}가 아니면 variants 객체가 없거나 깨진 URL일 수 있으므로 응답에서 제외한다(깨진 블러본
   * 노출 방지). 공개 피드 조회의 전부-READY 게이트(§4-a)와 같은 취지지만, 여기서는 공개된 원본까지 함께 막지
   * 않도록 미공개 사진에만 READY를 요구한다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §3·§4)
   *
   * @param memberId 사진을 공개할 대상 회원 id(채팅 본인 또는 상대)
   * @param revealCount 공개할 원본 장수 N(0~5). 음수는 0으로 취급한다.
   * @param role 공개 대상이 요청자 본인({@code ME})인지 상대({@code PARTNER})인지(이슈 #85). 응답 사진에 그대로 표기한다.
   * @return 사진별 소유자 관점·공개 여부와 URL을 {@code displayOrder} 순서로 담은 응답
   */
  @Transactional(readOnly = true)
  public RevealedFeedImagesResponse issueRevealedImages(
      Long memberId, int revealCount, RevealedFeedImagesResponse.Role role) {
    List<FeedImage> images = feedRepository.findByMemberId(memberId)
        .map(feed -> feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feed.getId()))
        .orElseGet(List::of);
    int revealable = Math.min(Math.max(revealCount, 0), images.size());

    List<RevealedFeedImagesResponse.Image> result = new ArrayList<>(images.size());
    for (int i = 0; i < images.size(); i++) {
      FeedImage image = images.get(i);
      if (i < revealable) {
        // 공개된 원본: originals 객체는 블러본 생성 상태와 무관하게 존재하므로 항상 Presigned GET으로 제공한다.
        result.add(RevealedFeedImagesResponse.Image.revealed(
            role, image, storageService.presignOriginalDownload(image.getOriginalKey())));
      } else if (image.isReady()) {
        // 미공개 사진은 블러본으로 제공하되, 블러본이 확인된(READY) 경우만 노출해 깨진 URL을 막는다.
        result.add(RevealedFeedImagesResponse.Image.blurred(
            role, image, storageService.toPublicVariantUrl(image.getVariantKey())));
      }
      // 미공개 + 블러본 미생성(PROCESSING/FAILED)은 깨진 블러본 노출을 막기 위해 응답에서 제외한다.
    }
    return new RevealedFeedImagesResponse(result);
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
