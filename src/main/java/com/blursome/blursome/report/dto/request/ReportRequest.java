package com.blursome.blursome.report.dto.request;

import com.blursome.blursome.report.domain.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청.
 *
 * @param targetMemberId 신고 대상 회원 id
 * @param chatRoomId     채팅방 신고면 방 id, 프로필 신고면 {@code null}
 * @param reason         신고 사유. 화면 신고 항목과 1:1로 대응한다({@link ReportReason} 참고)
 * @param detail         상세 설명(선택)
 */
public record ReportRequest(
    @NotNull(message = "신고 대상 회원 id는 필수입니다.")
    Long targetMemberId,

    Long chatRoomId,

    @Schema(description = "신고 사유. 화면 항목과 1:1 대응 — "
        + "INAPPROPRIATE_CHAT=불쾌한 대화, SEXUAL_HARASSMENT=성적인 내용, COERCIVE_MEETING=만남/약속 강요, "
        + "DANGEROUS_PERSON=위험한 사람, FINANCIAL_OR_PERSONAL_INFO_REQUEST=금전·개인정보 요구, "
        + "SPAM=광고·홍보, ETC=기타. 목록에 없는 값은 400.",
        allowableValues = {"INAPPROPRIATE_CHAT", "SEXUAL_HARASSMENT", "COERCIVE_MEETING",
            "DANGEROUS_PERSON", "FINANCIAL_OR_PERSONAL_INFO_REQUEST", "SPAM", "ETC"},
        example = "INAPPROPRIATE_CHAT")
    @NotNull(message = "신고 사유는 필수입니다.")
    ReportReason reason,

    @Size(max = 500, message = "상세 설명은 500자 이하여야 합니다.")
    String detail
) {

}
