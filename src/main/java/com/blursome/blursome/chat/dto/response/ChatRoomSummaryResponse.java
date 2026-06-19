package com.blursome.blursome.chat.dto.response;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;

/** 방 목록/단건 조회 응답. {@code unreadCount}는 조회 시점에 실측 계산한 값이다(설계 §7-4). */
public record ChatRoomSummaryResponse(
    Long roomId,
    ChatRoomStatus roomStatus,
    ChatRoomProgressStatus progressStatus,
    Long lastMessageId,
    long unreadCount
) {

  public static ChatRoomSummaryResponse of(ChatRoom room, long unreadCount) {
    return new ChatRoomSummaryResponse(
        room.getId(),
        room.getRoomStatus(),
        room.getProgressStatus(),
        room.getLastMessageId(),
        unreadCount
    );
  }
}
