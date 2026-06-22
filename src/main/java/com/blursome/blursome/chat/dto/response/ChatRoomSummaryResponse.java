package com.blursome.blursome.chat.dto.response;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;

/**
 * 방 목록/단건 조회 응답. {@code unreadCount}는 조회 시점에 실측 계산한 값이다(설계 §7-4).
 * {@code progressStatus}로 방의 진행 단계를, {@code lastMessage}로 마지막 메시지 미리보기를 함께 내려준다.
 * 메시지가 한 번도 없는 방은 {@code lastMessageId}와 {@code lastMessage}가 모두 null이다.
 *
 * <p>{@code partnerNickname}은 1:1 상대의 닉네임이다(온보딩 전이면 null). {@code partnerLastReadMessageId}는 상대가
 * 마지막으로 읽은 메시지 id로(읽음 표시용), 내가 보낸 메시지 중 {@code 메시지 id <= partnerLastReadMessageId}인 것을
 * "상대가 읽음"으로 표시하면 된다. 상대가 아직 아무것도 읽지 않았으면 null이며, 실시간 갱신은
 * {@code /topic/rooms/{roomId}}로 흐르는 {@code READ} 이벤트로 받는다.
 */
public record ChatRoomSummaryResponse(
    Long roomId,
    ChatRoomStatus roomStatus,
    ChatRoomProgressStatus progressStatus,
    String partnerNickname,
    Long lastMessageId,
    ChatMessageResponse lastMessage,
    Long partnerLastReadMessageId,
    long unreadCount
) {

  public static ChatRoomSummaryResponse of(ChatRoom room, String partnerNickname,
      ChatMessageResponse lastMessage, Long partnerLastReadMessageId, long unreadCount) {
    return new ChatRoomSummaryResponse(
        room.getId(),
        room.getRoomStatus(),
        room.getProgressStatus(),
        partnerNickname,
        room.getLastMessageId(),
        lastMessage,
        partnerLastReadMessageId,
        unreadCount
    );
  }
}
