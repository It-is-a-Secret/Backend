package com.blursome.blursome.report.dto.request;

import com.blursome.blursome.report.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청.
 *
 * @param targetMemberId 신고 대상 회원 id
 * @param chatRoomId     채팅방 신고면 방 id, 프로필 신고면 {@code null}
 * @param reason         신고 사유
 * @param detail         상세 설명(선택)
 */
public record ReportRequest(
    @NotNull(message = "신고 대상 회원 id는 필수입니다.")
    Long targetMemberId,

    Long chatRoomId,

    @NotNull(message = "신고 사유는 필수입니다.")
    ReportReason reason,

    @Size(max = 500, message = "상세 설명은 500자 이하여야 합니다.")
    String detail
) {

}
