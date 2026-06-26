package com.blursome.blursome.chat.domain;

public enum ChatRoomStatus {
  ACTIVE,
  CLOSED,
  // 차단으로 동결된 경우(이슈 #77). 가역 — 양방향 차단 해제 시 ACTIVE로 복구. 동결 중 송신·단계 진행 차단.
  BLOCKED,
  // 신고 누적으로 동결된 경우(#75). 운영자 검토 대기, 송신·조회 차단.
  REPORTED
}
