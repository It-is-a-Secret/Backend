package com.blursome.blursome.discovery.controller;

import com.blursome.blursome.discovery.dto.request.ChatStartRequest;
import com.blursome.blursome.discovery.dto.response.ChatStartResponse;
import com.blursome.blursome.discovery.dto.response.DiscoveryCardDetailResponse;
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
          + "동점은 최근 7일 접속 우선으로 가른다. 다음 페이지는 page를 1씩 올려 요청한다.\n\n"
          + "온보딩 전 조회자(#122): 성별·관심사가 아직 없어 성별 구분 없이 공개 피드(활성·온보딩 완료·사진 5장 READY·"
          + "차단 아님)를 점수 없이 최근 접속순(최근 7일 접속 우선 → 마지막 접속 → feedId 내림차순)으로 보여준다. "
          + "대화 시작은 온보딩 완료 후에만 가능하다.\n\n"
          + "기존 대화(#112): 이미 대화 중인 상대는 점수와 무관하게 목록 뒤쪽에 오고, 카드의 chatRoom(roomId, roomStatus)이 "
          + "채워진다. 이 경우 첫 메시지 입력 없이 roomId로 기존 채팅방에 바로 들어간다. 종료(CLOSED)·검토 중(REPORTED) "
          + "관계인 상대는 다시 대화할 수 없으므로 목록에 나오지 않는다. 채팅방이 없으면 chatRoom은 null.")
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

  @Operation(summary = "탐색 카드 상세 조회",
      description = "선택한 카드의 기본 정보(닉네임·생년·학과·MBTI·성별)와 관심사 키워드를 반환한다. 키워드마다 "
          + "조회자도 선택한 태그면 matched=true. 사진은 GET /api/feeds/{feedId}/images로 따로 조회한다. "
          + "탐색 목록과 같은 노출 조건(이성·활성·차단 아님·사진 5장 READY)을 재검증하며, 대상이 없거나 조건을 "
          + "충족하지 않으면 사유 구분 없이 404. 종료(CLOSED)·검토 중(REPORTED) 관계인 상대도 404. 이미 대화 중이면 "
          + "chatRoom(roomId, roomStatus)이 채워진다(#112). 온보딩 전 조회자는 이성 조건 없이 검증하고 matched는 "
          + "모두 false다(#122).")
  @GetMapping("/feeds/{feedId}")
  public ResponseEntity<DataResponse<DiscoveryCardDetailResponse>> getCardDetail(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long feedId
  ) {
    return ResponseEntity.ok(DataResponse.ok(discoveryService.getCardDetail(memberId, feedId)));
  }

  @Operation(summary = "피드로 1:1 대화 시작",
      description = "탐색에서 발견한 상대의 feedId로 대화를 시작한다. 첫 접촉이면 방을 만들고 첫 메시지를 전송한 뒤 "
          + "200(created=true, firstMessage 포함), 이미 채팅 중(ACTIVE)이면 보낸 메시지를 저장하지 않고 기존 방으로 안내한다"
          + "(200, created=false, firstMessage=null). 이미 채팅 중인지는 탐색 카드의 chatRoom으로 미리 알 수 있으니, "
          + "그 경우 이 API 대신 roomId로 바로 이동한다. 대상 피드 없음 404, 자기 자신 400, 온보딩 미완료 403"
          + "(ONBOARDING_REQUIRED), 내 피드 미완성 403, "
          + "이성 아님·대상 피드 비공개·비활성 409(NOT_ELIGIBLE), 차단 관계 409(BLOCKED), 종료된 관계 409(RELATIONSHIP_CLOSED), 검토 중 "
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
