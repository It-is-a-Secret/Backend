package com.blursome.blursome.block.dto.response;

import com.blursome.blursome.block.domain.Block;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 차단 목록의 한 항목(#113). 내가 차단한 회원과 차단 시각을 담는다.
 *
 * <p>{@code blockedMemberId}는 차단 해제({@code DELETE /api/blocks/{targetMemberId}})에 그대로 쓴다.
 */
public record BlockedMemberResponse(
    @Schema(description = "차단한 회원 id. 차단 해제 요청의 targetMemberId로 사용한다.", example = "1001")
    Long blockedMemberId,
    @Schema(description = "차단한 회원의 닉네임(온보딩 전이면 null)", nullable = true, example = "blur")
    String nickName,
    @Schema(description = "차단한 시각(한국 시간, +09:00)", example = "2026-10-28T13:22:14.828729+09:00")
    LocalDateTime blockedAt
) {

  public static BlockedMemberResponse from(Block block) {
    return new BlockedMemberResponse(
        block.getBlocked().getId(),
        block.getBlocked().getNickName(),
        block.getCreatedAt()
    );
  }
}
