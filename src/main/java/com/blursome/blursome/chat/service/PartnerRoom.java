package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoomStatus;

/**
 * 두 회원 사이에 존재하는 1:1 방과 그 상태(#112). 회원 쌍당 방은 영구히 하나이므로({@code pair_key} 유니크) 관계를
 * 이 값 하나로 표현한다. 탐색 도메인이 "이미 대화 중"·"다시 시작할 수 없는 관계"를 판정하는 데 쓴다.
 *
 * @param roomId 방 id
 * @param roomStatus 방 상태
 */
public record PartnerRoom(Long roomId, ChatRoomStatus roomStatus) {

  /** 이미 대화 중인 방인지 여부. 탐색 카드에서 첫 메시지 입력 대신 기존 방으로 바로 이동시킨다. */
  public boolean isActive() {
    return roomStatus == ChatRoomStatus.ACTIVE;
  }

  /**
   * 탐색에서 새 대화를 시작할 수 없는 관계인지 여부. {@code CLOSED}는 관계 영구 종료, {@code REPORTED}는 신고 검토 대기라
   * 대화 시작 요청이 항상 409로 거절되므로 탐색 후보에서 뺀다. {@code BLOCKED}는 차단 관계라 차단 필터가 이미 뺀다.
   */
  public boolean excludesFromDiscovery() {
    return roomStatus == ChatRoomStatus.CLOSED || roomStatus == ChatRoomStatus.REPORTED;
  }
}
