package com.blursome.blursome.feed.controller;

import com.blursome.blursome.feed.dto.response.PublicFeedImagesResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
          + "없으면 404로 비노출한다. 조회자는 학교 이메일 인증을 마쳐야 하며(온보딩은 불필요) 미인증이면 "
          + "403(MEMBER_403_SCHOOL_VERIFICATION_REQUIRED). 피드 주인이 탈퇴·정지·온보딩 미완료이거나 조회자와 "
          + "차단 관계(양방향)면 사유 구분 없이 404(#129).")
  @GetMapping("/{feedId}/images")
  public ResponseEntity<DataResponse<PublicFeedImagesResponse>> getPublicFeedImages(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long feedId
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(feedImageService.getPublicFeedImages(memberId, feedId)));
  }
}
