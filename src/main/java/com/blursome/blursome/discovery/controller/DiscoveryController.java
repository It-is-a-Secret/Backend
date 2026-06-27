package com.blursome.blursome.discovery.controller;

import com.blursome.blursome.discovery.dto.request.ChatStartRequest;
import com.blursome.blursome.discovery.dto.response.ChatStartResponse;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardResponse;
import com.blursome.blursome.discovery.service.ChatStartService;
import com.blursome.blursome.discovery.service.DiscoveryService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private final ChatStartService chatStartService;

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

  @Operation(summary = "피드로 1:1 대화 시작",
      description = "탐색에서 발견한 상대의 feedId로 대화를 시작한다. 첫 접촉이면 방을 만들고 첫 메시지를 전송한 뒤 "
          + "200(created=true, firstMessage 포함), 이미 채팅 중(ACTIVE)이면 메시지 없이 기존 방으로 안내한다"
          + "(200, created=false). 대상 피드 없음 404, 자기 자신 400, 내 피드 미완성 403, 이성 아님·대상 피드 비공개·"
          + "비활성 409(NOT_ELIGIBLE), 차단 관계 409(BLOCKED), 종료된 관계 409(RELATIONSHIP_CLOSED), 검토 중 "
          + "409(RELATIONSHIP_UNDER_REVIEW).")
  @PostMapping("/feeds/{feedId}/chat")
  public ResponseEntity<DataResponse<ChatStartResponse>> startChat(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long feedId,
      @RequestBody @Valid ChatStartRequest request
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(chatStartService.startChat(memberId, feedId, request.message())));
  }
}
