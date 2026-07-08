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

  @Operation(summary = "피드 이미지 메타데이터 저장 (full-replace)",
      description = "프론트가 원본 S3 업로드를 마친 뒤 피드 이미지 메타데이터를 저장한다. 요청 목록을 피드 사진 전체 집합으로 "
          + "보는 full-replace 방식이며(기존 DB 행 대체, S3 객체 즉시 삭제 안 함), 원본 key로부터 variant_key를 계산하고 "
          + "processing_status=PROCESSING으로 시작한다. 인증 주체에서 피드를 도출하므로 feedId를 받지 않는다.")
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
