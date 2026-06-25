package com.blursome.blursome.discovery.controller;

import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.service.DiscoveryService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Discovery", description = "탐색(이성 회원 추천) API")
@RestController
@RequestMapping("/api/discovery")
@RequiredArgsConstructor
public class DiscoveryController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final DiscoveryService discoveryService;

  @Operation(summary = "탐색 목록 조회",
      description = "온보딩 완료한 이성 회원을 가중 점수(키워드·MBTI·년생·학과)순으로 정렬해 page/size 페이지네이션한다. "
          + "동점은 최근 7일 접속 우선으로 가른다. 다음 페이지는 page를 1씩 올려 요청한다. 온보딩 미완료자는 403.")
  @GetMapping
  public ResponseEntity<DataResponse<List<DiscoveryCardResponse>>> getDiscovery(
      @AuthenticationPrincipal Long memberId,
      @Parameter(description = "0부터 시작하는 페이지 번호(음수는 0으로 보정)")
      @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "페이지 크기(1~50으로 보정됨)")
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size
  ) {
    return ResponseEntity.ok(DataResponse.ok(discoveryService.getDiscovery(memberId, page, size)));
  }
}
