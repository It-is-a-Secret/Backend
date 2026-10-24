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
import com.blursome.blursome.global.exception.code.GlobalErrorCode;
import com.blursome.blursome.global.storage.S3ObjectKeyGenerator;
import com.blursome.blursome.global.storage.S3StorageService;
import com.blursome.blursome.global.storage.S3StorageService.PresignedUpload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * 피드 이미지 도메인 서비스. 원본 업로드용 Presigned PUT 발급, 피드 사진 저장(full-replace)·블러 재처리,
 * 본인·공개·채팅 공개용 조회를 담당한다.
 *
 * <p>발급은 DB를 거치지 않는다. 회원 id로 결정론적 원본 key를 만들고({@link S3ObjectKeyGenerator}),
 * blur_level을 메타데이터로 박은 Presigned PUT을 발급한다({@link S3StorageService}). 실제 {@link FeedImage}
 * 행은 프론트가 S3 업로드를 마친 뒤 {@link #replaceImages} 메타데이터 저장 API에서 생성된다.
 */
@Slf4j
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
   * 회원 피드의 이미지를 요청 목록으로 <b>전체 대체(full-replace)</b>한다. 요청 목록을 피드 사진의 최종 전체 집합으로
   * 보고 현재 목록과 비교해 반영하므로, 유지·삭제·교체·재정렬·추가·블러 변경이 한 요청으로 처리된다(#105).
   *
   * <ul>
   *   <li>{@code id} 항목: 기존 행을 유지한다. ID·key·처리 상태는 그대로 두고 순서만 바꾼다.</li>
   *   <li>{@code id} 항목 + 다른 {@code blurLevel}: 원본을 새 key로 복사해 Lambda를 다시 실행하고, 같은 행의
   *       key·블러 강도를 바꿔 {@code PROCESSING}부터 다시 추적한다(copy-on-reprocess).</li>
   *   <li>{@code objectKey} 항목: 새 행을 {@code PROCESSING}으로 만든다. {@code variantKey}는 원본 key에서
   *       결정론적으로 계산한다.</li>
   *   <li>현재 목록에 있지만 요청에 없는 행: 삭제한다.</li>
   * </ul>
   *
   * <p>피드 행을 비관적 락으로 먼저 잠가 같은 회원의 동시 저장을 직렬화한다. S3 복사는 DB를 바꾸기 전에 모두 마치므로,
   * 복사가 실패하면 DB는 그대로다. 복사 후 DB가 롤백되면 새 객체는 참조되지 않는 고아로 남는다. 저장은
   * <b>DB 행만 대체</b>하며 기존 S3 객체를 즉시 삭제하지 않는다. 제거·재처리로 참조가 끊긴 객체는 후속 cleanup으로
   * 정리한다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §8-2·§8-3)
   *
   * @param memberId 인증된 회원 id. 본인 피드(회원:피드 1:1)를 도출하고, 기존 사진 id는 이 피드 소속인지 검증한다.
   * @param request 저장할 사진 목록. 장수(1~5)·순서/블러 범위는 DTO에서 검증된다.
   * @return 저장 후 피드 사진 전체를 노출 순서대로 담은 응답
   * @throws BaseException 피드가 없거나({@code FEED_404_NOT_FOUND}), id/objectKey 조합이 잘못됐거나
   *     ({@code FEED_IMAGE_400_INVALID_REFERENCE}), 순서·참조가 중복됐거나({@code FEED_IMAGE_400_DUPLICATE_ORDER},
   *     {@code FEED_IMAGE_400_DUPLICATE_REFERENCE}), 원본 key가 회원 소유가 아니거나
   *     ({@code FEED_IMAGE_400_INVALID_OBJECT_KEY}), 기존 사진 id가 이 피드에 없는 경우({@code FEED_IMAGE_404_NOT_FOUND})
   */
  @Transactional
  public FeedImageResponse replaceImages(Long memberId, FeedImageSaveRequest request) {
    Feed feed = feedRepository.findByMemberIdForUpdate(memberId)
        .orElseThrow(() -> BaseException.from(FeedErrorCode.FEED_NOT_FOUND));
    List<FeedImageSaveRequest.Image> items = request.images();
    validateSingleReference(items);
    validateNoDuplicateOrder(items);
    validateNoDuplicateReference(items);
    List<FeedImageSaveRequest.Image> uploads = items.stream()
        .filter(item -> !item.isExisting())
        .toList();
    validateOwnedObjectKeys(memberId, uploads);

    List<FeedImage> currentImages =
        feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feed.getId());
    List<KeptImage> keptImages = resolveKeptImages(items, currentImages);
    validateUploadsNotKept(uploads, keptImages);

    // S3 복사를 DB 변경보다 먼저 끝내, 복사 실패 시 DB가 바뀌지 않게 한다.
    Map<Long, ReprocessedKeys> reprocessed = copyOriginalsForReprocessing(memberId, keptImages);

    deleteDroppedImages(currentImages, keptImages);
    reorderKeptImages(keptImages);
    keptImages.forEach(kept -> {
      ReprocessedKeys keys = reprocessed.get(kept.image().getId());
      if (keys != null) {
        kept.image().startReprocessing(keys.originalKey(), keys.variantKey(), kept.blurLevel());
      }
    });
    // 유지 행의 최종 순서를 먼저 반영해야, 신규 행이 비워진 순서를 받을 때 유니크 제약과 충돌하지 않는다.
    feedImageRepository.flush();

    List<FeedImage> saved = feedImageRepository.saveAll(
        uploads.stream()
            .map(upload -> FeedImage.create(
                feed,
                upload.displayOrder(),
                upload.objectKey(),
                keyGenerator.toVariantKey(upload.objectKey()),
                upload.blurLevel()))
            .toList());

    List<FeedImage> finalImages = new ArrayList<>(keptImages.size() + saved.size());
    keptImages.forEach(kept -> finalImages.add(kept.image()));
    finalImages.addAll(saved);
    return new FeedImageResponse(finalImages.stream()
        .sorted(Comparator.comparing(FeedImage::getDisplayOrder))
        .map(image -> FeedImageResponse.Image.of(image,
            storageService.toPublicVariantUrl(image.getVariantKey())))
        .toList());
  }

  /**
   * 요청의 {@code id} 항목을 현재 피드 이미지와 짝짓는다. 다른 피드(다른 회원)의 id나 없는 id는 존재 여부를 드러내지
   * 않도록 모두 {@code FEED_IMAGE_404_NOT_FOUND}로 거부한다. {@code blurLevel}을 생략하면 현재 값을 유지한다.
   */
  private List<KeptImage> resolveKeptImages(
      List<FeedImageSaveRequest.Image> items, List<FeedImage> currentImages) {
    Map<Long, FeedImage> currentById = new HashMap<>();
    currentImages.forEach(image -> currentById.put(image.getId(), image));

    List<KeptImage> keptImages = new ArrayList<>();
    for (FeedImageSaveRequest.Image item : items) {
      if (!item.isExisting()) {
        continue;
      }
      FeedImage image = currentById.get(item.id());
      if (image == null) {
        throw BaseException.from(FeedImageErrorCode.NOT_FOUND);
      }
      int blurLevel = item.blurLevel() != null ? item.blurLevel() : image.getBlurLevel();
      keptImages.add(new KeptImage(image, item.displayOrder(), blurLevel));
    }
    return keptImages;
  }

  /**
   * 블러 강도가 바뀐 유지 사진의 원본을 새 key로 복사해 Lambda를 다시 실행시킨다. 기존 key에 덮어쓰면 스케줄러가
   * 이전 블러본을 보고 완료로 오판하거나 캐시가 이전 블러본을 계속 보여줄 수 있어 항상 새 key를 쓴다.
   *
   * @return 재처리 대상 이미지 id별 새 원본·블러본 key
   */
  private Map<Long, ReprocessedKeys> copyOriginalsForReprocessing(
      Long memberId, List<KeptImage> keptImages) {
    Map<Long, ReprocessedKeys> reprocessed = new HashMap<>();
    for (KeptImage kept : keptImages) {
      FeedImage image = kept.image();
      if (!image.needsReprocessing(kept.blurLevel())) {
        continue;
      }
      String newOriginalKey =
          keyGenerator.generateReprocessedOriginalKey(memberId, image.getOriginalKey());
      try {
        storageService.reprocessOriginal(image.getOriginalKey(), newOriginalKey, kept.blurLevel());
      } catch (SdkException e) {
        log.error("블러 재처리용 원본 복사 실패. memberId={}, feedImageId={}, sourceKey={}, targetKey={}",
            memberId, image.getId(), image.getOriginalKey(), newOriginalKey, e);
        throw BaseException.from(GlobalErrorCode.INTERNAL_SERVER_ERROR);
      }
      reprocessed.put(image.getId(),
          new ReprocessedKeys(newOriginalKey, keyGenerator.toVariantKey(newOriginalKey)));
    }
    return reprocessed;
  }

  /** 요청에 포함되지 않은 현재 사진만 삭제한다. 비워진 순서를 다른 행이 받을 수 있도록 바로 DB에 반영한다. */
  private void deleteDroppedImages(List<FeedImage> currentImages, List<KeptImage> keptImages) {
    Set<Long> keptIds = new HashSet<>();
    keptImages.forEach(kept -> keptIds.add(kept.image().getId()));
    List<FeedImage> dropped = currentImages.stream()
        .filter(image -> !keptIds.contains(image.getId()))
        .toList();
    if (dropped.isEmpty()) {
      return;
    }
    feedImageRepository.deleteAll(dropped);
    feedImageRepository.flush();
  }

  /**
   * 유지 사진의 순서를 바꾼다. 1↔2 교환처럼 서로의 순서를 맞바꾸면 중간 상태에서 {@code uk_feed_image_order}가
   * 충돌하므로, 순서가 바뀌는 행을 먼저 임시 순서(음수)로 옮겨 DB에 반영한 뒤 최종 순서를 지정한다.
   */
  private void reorderKeptImages(List<KeptImage> keptImages) {
    List<KeptImage> moved = keptImages.stream()
        .filter(kept -> !kept.image().getDisplayOrder().equals(kept.displayOrder()))
        .toList();
    if (moved.isEmpty()) {
      return;
    }
    moved.forEach(kept -> kept.image().moveToTemporaryDisplayOrder());
    feedImageRepository.flush();
    moved.forEach(kept -> kept.image().changeDisplayOrder(kept.displayOrder()));
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

  /**
   * 각 항목이 {@code id}와 {@code objectKey} 중 정확히 하나만 가졌는지 검증한다. Bean Validation으로 막으면 전역
   * 검증 오류 코드로 응답되므로, 클라이언트가 구별할 수 있도록 도메인 오류 코드로 서비스에서 거부한다.
   */
  private void validateSingleReference(List<FeedImageSaveRequest.Image> images) {
    if (!images.stream().allMatch(FeedImageSaveRequest.Image::hasSingleReference)) {
      throw BaseException.from(FeedImageErrorCode.INVALID_REFERENCE);
    }
  }

  /** 한 요청에서 같은 {@code id}나 같은 {@code objectKey}를 두 번 쓰지 않았는지 검증한다. */
  private void validateNoDuplicateReference(List<FeedImageSaveRequest.Image> images) {
    Set<Long> ids = new HashSet<>();
    Set<String> objectKeys = new HashSet<>();
    for (FeedImageSaveRequest.Image image : images) {
      boolean added = image.isExisting() ? ids.add(image.id()) : objectKeys.add(image.objectKey());
      if (!added) {
        throw BaseException.from(FeedImageErrorCode.DUPLICATE_REFERENCE);
      }
    }
  }

  /**
   * 새 사진의 {@code objectKey}가 함께 유지하는 기존 사진의 원본 key와 같지 않은지 검증한다. 같으면 한 원본을 두 행이
   * 참조하게 되므로 중복 참조로 거부한다. 요청에서 빠진(삭제되는) 사진의 key를 다시 보내는 것은 허용한다.
   */
  private void validateUploadsNotKept(
      List<FeedImageSaveRequest.Image> uploads, List<KeptImage> keptImages) {
    Set<String> keptKeys = new HashSet<>();
    keptImages.forEach(kept -> keptKeys.add(kept.image().getOriginalKey()));
    if (uploads.stream().anyMatch(upload -> keptKeys.contains(upload.objectKey()))) {
      throw BaseException.from(FeedImageErrorCode.DUPLICATE_REFERENCE);
    }
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

  /** 요청에서 유지하기로 한 기존 사진과 그 최종 순서·블러 강도. */
  private record KeptImage(FeedImage image, int displayOrder, int blurLevel) {
  }

  /** 블러 재처리로 새로 정한 원본·블러본 key. */
  private record ReprocessedKeys(String originalKey, String variantKey) {
  }
}
