package com.blursome.blursome.report.domain;

/**
 * 신고 사유(마스터 §5.5). {@code @Enumerated(EnumType.STRING)}으로 저장하며 추가는 append만 허용한다.
 */
public enum ReportReason {
  SPAM,
  INAPPROPRIATE_CHAT,
  SEXUAL_HARASSMENT,
  HATE_SPEECH,
  PHOTO_THEFT,
  ETC
}
