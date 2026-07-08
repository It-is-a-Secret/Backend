package com.blursome.blursome.block.controller;

import com.blursome.blursome.block.dto.request.BlockRequest;
import com.blursome.blursome.block.service.BlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
  @PostMapping
  public ResponseEntity<Void> block(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody BlockRequest request
  ) {
    blockService.block(memberId, request.targetMemberId());
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "차단 해제",
      description = "대상 회원 차단을 해제한다. 차단 상태가 아니어도 멱등하게 204.")
  @DeleteMapping("/{targetMemberId}")
  public ResponseEntity<Void> unblock(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long targetMemberId
  ) {
    blockService.unblock(memberId, targetMemberId);
    return ResponseEntity.noContent().build();
  }
}
