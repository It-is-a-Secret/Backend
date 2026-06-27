package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.chat.dto.response.ChatMessageResponse;

/**
 * 피드로 1:1 대화 시작 응답(이슈 #87).
 *
 * <p>첫 접촉이면 방을 새로 만들고 첫 메시지를 전송한 결과({@code created=true}, {@code firstMessage} 채워짐)를,
 * 이미 채팅 중(ACTIVE)이면 기존 방으로 안내하는 결과({@code created=false}, {@code firstMessage=null})를 담는다.
 * 두 경우 모두 HTTP 200이며, 클라이언트는 {@code roomId}로 채팅방에 진입한다. CLOSED/REPORTED/차단 등 시작이
 * 거부되는 경우는 200이 아니라 도메인 예외(409 등)로 응답한다.
 */
public record ChatStartResponse(
    Long roomId,
    boolean created,
    ChatMessageResponse firstMessage
) {

}
