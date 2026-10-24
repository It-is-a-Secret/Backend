package com.blursome.blursome.feed.controller;

import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.MyFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FeedImage", description = "피드 이미지 업로드 API (Presigned URL 직접 업로드)")
@RestController
@RequestMapping("/api/feeds/me/images")
@RequiredArgsConstructor
public class FeedImageController {

  private final FeedImageService feedImageService;

  @Operation(summary = "원본 업로드용 Presigned PUT URL 발급",
      description = "비공개 originals 버킷에 원본을 직접 PUT할 단기 URL을 사진별로 발급한다. blur_level을 원본 객체 "
          + "메타데이터(x-amz-meta-blur-level)로 서명에 고정하고, 프론트가 그대로 실어 보낼 requiredHeaders를 함께 내려준다.")
  @PostMapping("/presigned-urls")
  public ResponseEntity<DataResponse<PresignedUrlResponse>> issuePresignedUrls(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody PresignedUrlRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(feedImageService.issuePresignedUploadUrls(memberId, request)));
  }

  @Operation(summary = "피드 이미지 저장 (full-replace, 기존·신규 혼합)",
      description = "저장 후 피드에 남을 사진 전체 목록(1~5장)을 보낸다. 각 항목은 기존 사진이면 관리 조회 응답의 id, "
          + "새로 업로드한 사진이면 Presigned 발급 응답의 originalKey를 objectKey로 보내며, 둘 중 정확히 하나만 지정한다. "
          + "현재 피드에 있지만 목록에 없는 사진은 삭제된다(S3 객체는 즉시 삭제하지 않음).\n\n"
          + "- 기존 사진 유지·재정렬: id와 새 displayOrder. ID·블러본 URL·처리 상태가 그대로 유지된다.\n"
          + "- 기존 사진 블러 변경: id와 다른 blurLevel. 서버가 원본을 새 key로 복사해 블러본을 다시 만들며, "
          + "응답은 새 variantUrl과 PROCESSING이다. 별도 재처리 API는 없다. blurLevel 생략 시 현재 값 유지.\n"
          + "- 새 사진: objectKey. 원본 업로드가 끝난 key여야 하며 PROCESSING으로 생성된다. blurLevel은 Presigned "
          + "발급 때 지정한 값이 적용되고, 보낸다면 그 값과 같아야 한다(FEED_IMAGE_400_BLUR_LEVEL_MISMATCH).\n\n"
          + "인증 주체에서 피드를 도출하므로 feedId를 받지 않는다. 다른 회원 사진 id는 FEED_IMAGE_404_NOT_FOUND, "
          + "id/objectKey 조합 오류는 FEED_IMAGE_400_INVALID_REFERENCE, 같은 사진 중복 지정은 "
          + "FEED_IMAGE_400_DUPLICATE_REFERENCE, 업로드되지 않은 objectKey는 FEED_IMAGE_400_ORIGINAL_NOT_UPLOADED다.")
  @PostMapping
  public ResponseEntity<DataResponse<FeedImageResponse>> saveImages(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody FeedImageSaveRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(feedImageService.replaceImages(memberId, request)));
  }

  @Operation(summary = "본인 피드 이미지 관리 조회",
      description = "본인 피드 이미지를 displayOrder 순서로 조회한다. 공개 조회와 달리 PROCESSING/FAILED도 함께 "
          + "내려 업로드 진행/실패를 보여주며, FAILED가 하나라도 있으면 reuploadRequired=true로 재업로드를 안내한다. "
          + "인증 주체에서 본인 피드를 도출하므로 feedId를 받지 않는다.")
  @GetMapping
  public ResponseEntity<DataResponse<MyFeedImagesResponse>> getMyImages(
      @AuthenticationPrincipal Long memberId
  ) {
    return ResponseEntity.ok(DataResponse.ok(feedImageService.getMyFeedImages(memberId)));
  }
}
