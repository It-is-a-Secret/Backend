package com.blursome.blursome.feed.controller;

import com.blursome.blursome.feed.dto.response.PublicFeedImagesResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 피드 공개 조회 API. 다른 회원이 보는 공개 피드(블러본)를 다룬다. 본인 피드 관리 조회·업로드는
 * {@link FeedImageController}({@code /api/feeds/me/images})가 담당한다.
 */
@Tag(name = "Feed", description = "피드 공개 조회 API")
@RestController
@RequestMapping("/api/feeds")
@RequiredArgsConstructor
public class FeedController {

  private final FeedImageService feedImageService;

  @Operation(summary = "공개 피드 이미지 조회",
      description = "다른 회원이 보는 공개 피드 이미지를 displayOrder 순서로 조회한다. 깨진/처리중 블러본 노출을 막기 "
          + "위해 모든 이미지가 READY일 때만 노출하며(전부-또는-비노출), 하나라도 PROCESSING/FAILED이거나 이미지가 "
          + "없으면 404로 비노출한다.")
  @GetMapping("/{feedId}/images")
  public ResponseEntity<DataResponse<PublicFeedImagesResponse>> getPublicFeedImages(
      @PathVariable Long feedId
  ) {
    return ResponseEntity.ok(DataResponse.ok(feedImageService.getPublicFeedImages(feedId)));
  }
}
