package com.blursome.blursome.block.controller;

import com.blursome.blursome.block.dto.request.BlockRequest;
import com.blursome.blursome.block.dto.response.BlockedMemberResponse;
import com.blursome.blursome.block.service.BlockService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Block", description = "차단 API")
@RestController
@RequestMapping("/api/blocks")
@RequiredArgsConstructor
public class BlockController {

  private final BlockService blockService;

  @Operation(summary = "차단 등록",
      description = "대상 회원을 차단한다. 탐색 후보에서는 양방향 제외되고, 채팅 v1에서는 차단자에게만 "
          + "목록·이력이 숨겨지며 기존 방 송신은 양쪽 모두 막힌다. 자기 차단은 400, 대상이 없으면 404. "
          + "이미 차단했으면 멱등하게 204.")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PostMapping
  public ResponseEntity<Void> block(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody BlockRequest request
  ) {
    blockService.block(memberId, request.targetMemberId());
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "차단 목록 조회",
      description = "내가 차단한 회원 목록을 최근 차단 순으로 반환한다. 각 항목의 blockedMemberId를 차단 해제 "
          + "(DELETE /api/blocks/{targetMemberId})에 그대로 쓴다. 탈퇴한 회원은 제외하며, 차단한 회원이 없으면 빈 목록.")
  @GetMapping
  public ResponseEntity<DataResponse<List<BlockedMemberResponse>>> getBlockedMembers(
      @AuthenticationPrincipal Long memberId
  ) {
    return ResponseEntity.ok(DataResponse.ok(blockService.getBlockedMembers(memberId)));
  }

  @Operation(summary = "차단 해제",
      description = "대상 회원 차단을 해제한다. 차단 상태가 아니어도 멱등하게 204.")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @DeleteMapping("/{targetMemberId}")
  public ResponseEntity<Void> unblock(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long targetMemberId
  ) {
    blockService.unblock(memberId, targetMemberId);
    return ResponseEntity.noContent().build();
  }
}
