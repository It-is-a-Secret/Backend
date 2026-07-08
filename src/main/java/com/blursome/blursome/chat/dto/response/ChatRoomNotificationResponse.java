package com.blursome.blursome.chat.dto.response;

/**
 * 유저 단위 개인 알림 큐({@code /user/queue/rooms})로 내려가는 페이로드(이슈 #88). 방 토픽
 * ({@code /topic/rooms/{roomId}})과 달리 회원은 접속 시 자신의 개인 큐 하나만 구독하면 어떤 방이든 "새 대화
 * 시작 / 새 메시지" 신호를 받는다. {@code /user/**}는 Spring이 세션 principal(=memberId)로 라우팅하므로
 * 타인 큐 구독이 원천 차단돼 방 토픽처럼 별도 참여자 검증이 필요 없다.
 *
 * <p><b>수신자 관점</b>으로 채운다 — {@code partnerId}/{@code partnerNickname}은 "내가 아닌 상대"(새 방
 * 개설자 또는 메시지 발신자)다. {@code message}는 마지막 메시지 미리보기(첫 메시지 또는 새 메시지)다.
 *
 * <p><b>멱등 신호 계약</b>: 클라이언트는 {@code (roomId, message.messageId)} 기준으로 상태를 <b>덮어쓰기</b>
 * 처리해야 한다(증분 금지). STOMP 재전송이나, 첫 접촉 시 같은 메시지에 대해 {@code NEW_ROOM}과 {@code NEW_MESSAGE}가
 * 모두 도착하는 중복에도 안읽음이 이중 계산되지 않게 하기 위함이다.
 *
 * @param type 신호 종류({@code NEW_ROOM}/{@code NEW_MESSAGE})
 * @param roomId 대상 방 id
 * @param partnerId 수신자 기준 상대 회원 id(개설자/발신자)
 * @param partnerNickname 상대 닉네임. 새 방 목록 항목 렌더용으로 {@code NEW_ROOM}에 싣는다.
 *                        {@code NEW_MESSAGE}는 수신자가 이미 목록에 상대 정보를 보유하므로 null일 수 있다.
 * @param message 마지막 메시지 미리보기(첫 메시지/새 메시지)
 */
public record ChatRoomNotificationResponse(
    ChatRoomNotificationType type,
    Long roomId,
    Long partnerId,
    String partnerNickname,
    ChatMessageResponse message
) {

  /** 첫 접촉으로 개설된 방을 상대(수신자)에게 알린다. 새 방 항목 렌더를 위해 개설자 닉네임을 함께 싣는다. */
  public static ChatRoomNotificationResponse newRoom(
      Long roomId, Long partnerId, String partnerNickname, ChatMessageResponse message) {
    return new ChatRoomNotificationResponse(
        ChatRoomNotificationType.NEW_ROOM, roomId, partnerId, partnerNickname, message);
  }

  /**
   * 기존 방의 새 메시지를 상대(수신자)에게 알린다. 수신자는 이미 상대 정보를 목록에 보유하므로 닉네임은 싣지
   * 않는다(필요한 식별자는 {@code partnerId}와 {@code message.senderId}로 충분).
   */
  public static ChatRoomNotificationResponse newMessage(
      Long roomId, Long partnerId, ChatMessageResponse message) {
    return new ChatRoomNotificationResponse(
        ChatRoomNotificationType.NEW_MESSAGE, roomId, partnerId, null, message);
  }
}
