package com.blursome.blursome.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.report.dto.request.ReportRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportReasonTest {

  @Test
  @DisplayName("신규 신고에서 고를 수 있는 사유는 화면 항목 7개와 1:1로 대응한다")
  void selectableReasons_matchScreenItems() {
    assertThat(Arrays.stream(ReportReason.values()).filter(ReportReason::isSelectable))
        .extracting(ReportReason::getLabel)
        .containsExactlyInAnyOrder("불쾌한 대화", "성적인 내용", "만남/약속 강요", "위험한 사람",
            "금전·개인정보 요구", "광고·홍보", "기타");
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("화면에서 빠진 과거 사유는 enum에 남지만 신규 신고에서는 고를 수 없다")
  void removedReasons_areKeptButNotSelectable() {
    assertThat(ReportReason.HATE_SPEECH.isSelectable()).isFalse();
    assertThat(ReportReason.PHOTO_THEFT.isSelectable()).isFalse();
  }

  @Test
  @DisplayName("Swagger 허용 값(ReportRequest.reason)은 선택 가능한 사유 목록과 정확히 같다")
  void swaggerAllowableValues_matchSelectableReasons() throws Exception {
    Schema schema = ReportRequest.class.getDeclaredField("reason").getAnnotation(Schema.class);
    List<String> selectable = Arrays.stream(ReportReason.values())
        .filter(ReportReason::isSelectable).map(Enum::name).toList();

    assertThat(schema.allowableValues()).containsExactlyInAnyOrderElementsOf(selectable);
  }

  @Test
  @DisplayName("저장 컬럼(VARCHAR 40)에 모든 사유 코드가 들어간다")
  void allCodes_fitColumnLength() {
    assertThat(ReportReason.values()).allSatisfy(r -> assertThat(r.name().length()).isLessThanOrEqualTo(40));
  }
}
