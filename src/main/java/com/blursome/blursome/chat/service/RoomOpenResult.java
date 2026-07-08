package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;

/**
 * {@link ChatRoomService#openRoom} 결과(이슈 #87). 방과 함께 "이번 호출로 새로 만들어졌는지"({@code created})와,
 * 새로 만든 경우 같은 트랜잭션에서 보낸 첫 메시지({@code firstMessage})를 돌려준다.
 *
 * <p>첫 접촉으로 방을 새로 만든 경우({@code created=true})에는 방 생성과 첫 메시지 전송이 <b>하나의 트랜잭션</b>으로
 * 처리돼 {@code firstMessage}가 채워진다(둘 중 하나라도 실패하면 둘 다 롤백 — 빈 방이 남지 않음). 이미 ACTIVE 방이
 * 있어 그대로 반환된 경우({@code created=false}, "이미 채팅 중")에는 메시지를 보내지 않으므로 {@code firstMessage=null}
 * 이다(동시 첫 접촉 경합에서 진 쪽도 동일 — 승자가 보낸 메시지를 중복 전송하지 않는다).
 */
public record RoomOpenResult(ChatRoom room, boolean created, ChatMessageResponse firstMessage) {

  /** 이미 존재하는 ACTIVE 방을 그대로 반환하는 결과(메시지 미전송). */
  public static RoomOpenResult existing(ChatRoom room) {
    return new RoomOpenResult(room, false, null);
  }
}
