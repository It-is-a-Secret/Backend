package com.blursome.blursome.chat.dto.response;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 방 목록/단건 조회 응답. {@code unreadCount}는 조회 시점에 실측 계산한 값이다(설계 §7-4).
 * {@code progressStatus}로 방의 진행 단계를, {@code lastMessage}로 마지막 메시지 미리보기를 함께 내려준다.
 * 메시지가 한 번도 없는 방은 {@code lastMessageId}와 {@code lastMessage}가 모두 null이다.
 *
 * <p>{@code partnerId}는 1:1 상대의 회원 id다(이슈 #100). 신고·차단 요청의 {@code targetMemberId}로 그대로 쓰며,
 * 상대가 메시지를 보낸 적이 없거나 방을 나간 뒤에도 내려간다.
 *
 * <p>{@code partnerNickname}은 1:1 상대의 닉네임이다(온보딩 전이면 null). {@code partnerLastReadMessageId}는 상대가
 * 마지막으로 읽은 메시지 id로(읽음 표시용), 내가 보낸 메시지 중 {@code 메시지 id <= partnerLastReadMessageId}인 것을
 * "상대가 읽음"으로 표시하면 된다. 상대가 아직 아무것도 읽지 않았으면 null이며, 실시간 갱신은
 * {@code /topic/rooms/{roomId}}로 흐르는 {@code READ} 이벤트로 받는다.
 */
public record ChatRoomSummaryResponse(
    @Schema(description = "채팅방 id", example = "1")
    Long roomId,
    @Schema(description = "방 상태")
    ChatRoomStatus roomStatus,
    @Schema(description = "사진 공개 진행 단계")
    ChatRoomProgressStatus progressStatus,
    @Schema(description = "1:1 상대 회원 id. 신고·차단 요청의 targetMemberId로 사용한다. "
        + "상대가 메시지를 보낸 적이 없거나 방을 나간 뒤에도 내려간다.", example = "1001")
    Long partnerId,
    @Schema(description = "상대 닉네임(온보딩 전이면 null)", nullable = true)
    String partnerNickname,
    @Schema(description = "마지막 메시지 id(메시지가 없으면 null)", nullable = true)
    Long lastMessageId,
    @Schema(description = "마지막 메시지 미리보기(메시지가 없으면 null)", nullable = true)
    ChatMessageResponse lastMessage,
    @Schema(description = "상대가 마지막으로 읽은 메시지 id(아직 안 읽었으면 null)", nullable = true)
    Long partnerLastReadMessageId,
    @Schema(description = "내가 안 읽은 메시지 수", example = "3")
    long unreadCount
) {

  public static ChatRoomSummaryResponse of(ChatRoom room, Long partnerId, String partnerNickname,
      ChatMessageResponse lastMessage, Long partnerLastReadMessageId, long unreadCount) {
    return new ChatRoomSummaryResponse(
        room.getId(),
        room.getRoomStatus(),
        room.getProgressStatus(),
        partnerId,
        partnerNickname,
        room.getLastMessageId(),
        lastMessage,
        partnerLastReadMessageId,
        unreadCount
    );
  }
}
