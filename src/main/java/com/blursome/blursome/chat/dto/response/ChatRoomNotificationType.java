package com.blursome.blursome.chat.dto.response;

/**
 * 유저 단위 개인 알림({@code /user/queue/rooms})의 신호 종류(이슈 #88).
 *
 * <ul>
 *   <li>{@code NEW_ROOM} — 첫 접촉으로 새 방이 개설됐다(이슈 #87). 수신자가 그 방을 구독한 적이 없어도 방
 *       목록에 새 항목을 즉시 추가할 수 있도록, 개설자(상대) 정보와 첫 메시지 미리보기를 함께 싣는다.</li>
 *   <li>{@code NEW_MESSAGE} — 이미 존재하는 방에 새 메시지가 도착했다. 수신자가 그 방을 구독 중이 아니어도
 *       방 목록·안읽음 배지를 갱신할 수 있도록 발신자 정보와 메시지 미리보기를 싣는다.</li>
 * </ul>
 */
public enum ChatRoomNotificationType {
  NEW_ROOM,
  NEW_MESSAGE
}
