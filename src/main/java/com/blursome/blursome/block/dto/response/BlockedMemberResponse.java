package com.blursome.blursome.block.dto.response;

import com.blursome.blursome.block.repository.BlockedMemberRow;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Mbti;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 차단 목록의 한 항목(#113). 내가 차단한 회원과 그 공개 프로필(생년·학과·MBTI, #118), 차단 시각을 담는다.
 *
 * <p>{@code blockedMemberId}는 차단 해제({@code DELETE /api/blocks/{targetMemberId}})에 그대로 쓴다. 공개 프로필 필드는
 * 탐색 카드와 같은 표기이며, 온보딩 전 회원(피드 없음)이면 모두 {@code null}이다.
 */
public record BlockedMemberResponse(
    @Schema(description = "차단한 회원 id. 차단 해제 요청의 targetMemberId로 사용한다.", example = "1001")
    Long blockedMemberId,
    @Schema(description = "차단한 회원의 닉네임(온보딩 전이면 null)", nullable = true, example = "blur")
    String nickName,
    @Schema(description = "출생 연도(온보딩 전이면 null)", nullable = true, example = "2001")
    Integer birthYear,
    @Schema(description = "학과 코드(온보딩 전이면 null)", nullable = true, example = "COMPUTER_ENGINEERING")
    Department department,
    @Schema(description = "학과 표시명(온보딩 전이면 null)", nullable = true, example = "컴퓨터공학과")
    String departmentLabel,
    @Schema(description = "MBTI(모름이거나 온보딩 전이면 null)", nullable = true, example = "INFP")
    Mbti mbti,
    @Schema(description = "차단한 시각(한국 시간, +09:00)", example = "2026-10-28T13:22:14.828729+09:00")
    LocalDateTime blockedAt
) {

  public static BlockedMemberResponse from(BlockedMemberRow row) {
    Department department = row.department();
    return new BlockedMemberResponse(
        row.blockedMemberId(),
        row.nickName(),
        row.birthYear(),
        department,
        department == null ? null : department.getLabel(),
        row.mbti(),
        row.blockedAt()
    );
  }
}
