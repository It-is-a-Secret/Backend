package com.blursome.blursome.chat.event;

import com.blursome.blursome.chat.dto.response.ChatRoomNotificationResponse;

/**
 * 특정 회원의 개인 알림 큐({@code /user/queue/rooms})로 알림 하나를 보내라고 알리는 도메인 이벤트(이슈 #88).
 * 방 토픽 브로드캐스트({@link ChatMessageBroadcastEvent})와 동일하게, 페이로드를 트랜잭션 안에서 완성해 싣고
 * 발행만 한 뒤 전송은 커밋 이후 리스너({@link ChatRoomNotificationListener})가 수행한다.
 *
 * <p>리스너는 {@code @TransactionalEventListener(AFTER_COMMIT)}로 받아 새 방·메시지가 DB에 실제 커밋된
 * 뒤에만 발행한다(롤백 시 미전송). {@code targetMemberId}는 STOMP principal name(=memberId 문자열)으로
 * 변환돼 {@code convertAndSendToUser}의 라우팅 키가 된다.
 *
 * @param targetMemberId 알림을 받을 회원 id(개인 큐 라우팅 대상)
 * @param notification 개인 큐로 내려갈 페이로드
 */
public record ChatRoomNotificationEvent(
    Long targetMemberId, ChatRoomNotificationResponse notification) {
}
