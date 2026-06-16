package com.blursome.blursome.chat.repository;

/** 방별 안읽음 메시지 수 집계 프로젝션 (목록 조회 시 N+1 회피용). */
public interface RoomUnreadCount {

  Long getRoomId();

  long getUnreadCount();
}
