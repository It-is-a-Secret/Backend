package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatRoomStatus;

/**
 * 회원이 참여했던 방별 "상대 회원 + 방 상태" 프로젝션(#112). 탐색에서 후보마다 기존 대화 관계를 한 번에 판정하는 데 쓴다.
 * 1:1 방이므로 방당 한 행이다.
 */
public interface PartnerRoomRelation {

  Long getPartnerId();

  Long getRoomId();

  ChatRoomStatus getRoomStatus();
}
