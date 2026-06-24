package com.blursome.blursome.feed.controller;

import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
}
