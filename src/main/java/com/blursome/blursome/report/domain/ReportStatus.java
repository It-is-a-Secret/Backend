package com.blursome.blursome.report.domain;

/**
 * 신고 처리 상태(운영자 라이프사이클). 접수 시 {@code RECEIVED}, 운영자 검토로 {@code RESOLVED}/{@code DISMISSED}.
 * 임계치(고유 신고자 수) 평가는 별도이며 본 상태와 독립이다.
 */
public enum ReportStatus {
  RECEIVED,
  RESOLVED,
  DISMISSED
}
