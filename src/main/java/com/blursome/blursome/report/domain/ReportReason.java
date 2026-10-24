package com.blursome.blursome.report.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 신고 사유(마스터 §5.5, 이슈 #102). 화면 신고 항목 7개와 1:1로 대응한다.
 *
 * <table>
 *   <tr><th>화면 문구</th><th>코드</th></tr>
 *   <tr><td>불쾌한 대화</td><td>{@link #INAPPROPRIATE_CHAT}</td></tr>
 *   <tr><td>성적인 내용</td><td>{@link #SEXUAL_HARASSMENT}</td></tr>
 *   <tr><td>만남/약속 강요</td><td>{@link #COERCIVE_MEETING}</td></tr>
 *   <tr><td>위험한 사람</td><td>{@link #DANGEROUS_PERSON}</td></tr>
 *   <tr><td>금전·개인정보 요구</td><td>{@link #FINANCIAL_OR_PERSONAL_INFO_REQUEST}</td></tr>
 *   <tr><td>광고·홍보</td><td>{@link #SPAM}</td></tr>
 *   <tr><td>기타</td><td>{@link #ETC}</td></tr>
 * </table>
 *
 * <p>{@code @Enumerated(EnumType.STRING)}으로 저장하므로 값 이름이 곧 저장 식별자다. 이미 저장된 행이 있으므로
 * <b>값 이름 변경·삭제는 금지</b>하고 추가만 허용한다. 화면에서 빠진 사유는 지우지 않고 {@code selectable=false}로
 * 남겨 과거 신고 행을 계속 읽을 수 있게 하되, 신규 신고에서는 받지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportReason {
  SPAM("광고·홍보", true),
  INAPPROPRIATE_CHAT("불쾌한 대화", true),
  /** 화면 문구 "성적인 내용". 기존 저장 데이터·클라이언트 호환을 위해 코드명은 유지한다. */
  SEXUAL_HARASSMENT("성적인 내용", true),
  /** 화면에서 제외됨. 과거 신고 행 조회용으로만 남긴다(신규 신고 불가). */
  @Deprecated
  HATE_SPEECH("혐오 발언", false),
  /** 화면에서 제외됨. 과거 신고 행 조회용으로만 남긴다(신규 신고 불가). */
  @Deprecated
  PHOTO_THEFT("사진 도용", false),
  ETC("기타", true),
  COERCIVE_MEETING("만남/약속 강요", true),
  DANGEROUS_PERSON("위험한 사람", true),
  FINANCIAL_OR_PERSONAL_INFO_REQUEST("금전·개인정보 요구", true);

  /** 화면 신고 항목 문구. */
  private final String label;
  /** 신규 신고에서 선택할 수 있는 사유인지. */
  private final boolean selectable;
}
