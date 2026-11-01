package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.service.PartnerRoom;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 탐색 카드에 붙는 "이 상대와 이미 있는 채팅방" 정보(#112). 방이 없으면 카드의 {@code chatRoom}은 null이다.
 *
 * <p>클라이언트는 {@code chatRoom}이 있으면 첫 메시지 입력 대신 {@code roomId}로 기존 채팅방에 바로 들어간다
 * ({@code GET /api/chat/rooms/{roomId}}). 대화 시작 API는 첫 메시지가 필수라 기존 방 입장 용도로 쓸 수 없다. 탐색에는
 * 다시 시작할 수 없는 관계({@code CLOSED}·{@code REPORTED})와 차단 관계가 나오지 않으므로, 목록에서는 사실상
 * {@code ACTIVE}만 내려간다.
 */
@Schema(name = "DiscoveryChatRoom", description = "탐색 카드의 상대와 이미 있는 채팅방. 없으면 카드의 chatRoom이 null")
public record DiscoveryChatRoomResponse(
    @Schema(description = "기존 채팅방 id. 첫 메시지 없이 이 방으로 바로 이동한다.", example = "31")
    Long roomId,
    @Schema(description = "방 상태. 탐색에는 다시 시작할 수 없는 관계가 나오지 않아 보통 ACTIVE다.")
    ChatRoomStatus roomStatus
) {

  /** 방이 없으면 null을 돌려준다. */
  public static DiscoveryChatRoomResponse from(PartnerRoom room) {
    return room == null ? null : new DiscoveryChatRoomResponse(room.roomId(), room.roomStatus());
  }
}
