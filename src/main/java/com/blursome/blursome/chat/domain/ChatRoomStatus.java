package com.blursome.blursome.chat.domain;

public enum ChatRoomStatus {
  ACTIVE,
  CLOSED,
  //차단된 경우(후 순위)
  BLOCKED,
  // 신고된 경우(후 순위)
  REPORTED
}
