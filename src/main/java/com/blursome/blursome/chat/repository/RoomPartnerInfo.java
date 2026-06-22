package com.blursome.blursome.chat.repository;

/**
 * 방별 "상대 참여자 정보"(닉네임 + 읽음 커서) 집계 프로젝션 (목록 조회 시 N+1 회피용).
 * 1:1 방 기준이므로 방당 한 행이며, 상대가 아직 아무것도 읽지 않았으면 {@code lastReadMessageId}가 null,
 * 온보딩 전이면 {@code partnerNickname}이 null일 수 있다.
 */
public interface RoomPartnerInfo {

  Long getRoomId();

  String getPartnerNickname();

  Long getLastReadMessageId();
}
