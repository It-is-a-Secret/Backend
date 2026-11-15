package com.blursome.blursome.notification.service;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.member.service.MemberService;
import com.blursome.blursome.notification.dto.ChatPushPayload;
import com.blursome.blursome.notification.dto.PushTarget;
import com.blursome.blursome.notification.sender.WebPushSender;
import com.blursome.blursome.notification.sender.WebPushSender.PushResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 채팅 새 메시지를 수신자의 모든 기기로 Web Push한다(이슈 #121).
 *
 * <p>STOMP는 화면 데이터 갱신, Web Push는 시스템 알림을 맡는다. 서버는 소켓 접속 상태를 보지 않고 항상 보낸다 — iOS는
 * 받은 푸시를 반드시 표시해야 하므로, 보낼지 말지는 여기서 정하고 표시하지 않을 알림은 처음부터 보내지 않는다.
 *
 * <p>트랜잭션을 걸지 않는다. 외부 HTTP 호출 동안 DB 커넥션을 잡지 않도록, 조회·결과 반영은 각 서비스의 짧은 트랜잭션으로
 * 나눈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPushService {

  private static final String TOPIC_PREFIX = "room-";

  private final PushSubscriptionService pushSubscriptionService;
  private final ChatRoomService chatRoomService;
  private final MemberService memberService;
  private final WebPushSender webPushSender;
  private final ObjectMapper objectMapper;

  /**
   * 새 메시지 푸시를 보낸다. 다음 경우에는 보내지 않는다: 푸시 미설정, SYSTEM 메시지, 발신자 본인, 수신 기기 없음, 수신자가
   * 나갔거나 방이 {@code ACTIVE}가 아님.
   *
   * @param recipientId 수신 회원 id
   * @param senderId 발신 회원 id
   * @param message 커밋된 메시지
   */
  public void sendNewMessage(Long recipientId, Long senderId, ChatMessageResponse message) {
    if (!webPushSender.isEnabled() || message.type() == ChatMessageType.SYSTEM
        || senderId == null || senderId.equals(recipientId)) {
      return;
    }
    List<PushTarget> targets = pushSubscriptionService.findTargets(recipientId);
    if (targets.isEmpty() || !chatRoomService.canReceiveMessagePush(message.roomId(), recipientId)) {
      return;
    }
    String nickname = memberService.findNickName(senderId).orElse(null);
    byte[] payload = serialize(ChatPushPayload.newMessage(message.roomId(), message.messageId(),
        nickname, message.type(), message.content()));
    if (payload == null) {
      return;
    }

    List<Long> delivered = new ArrayList<>();
    List<Long> expired = new ArrayList<>();
    String topic = TOPIC_PREFIX + message.roomId();
    for (PushTarget target : targets) {
      PushResult result = webPushSender.send(target, payload, topic);
      if (result == PushResult.DELIVERED) {
        delivered.add(target.subscriptionId());
      } else if (result == PushResult.EXPIRED) {
        expired.add(target.subscriptionId());
      }
    }
    pushSubscriptionService.recordResults(delivered, expired);
  }

  private byte[] serialize(ChatPushPayload payload) {
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException e) {
      log.warn("푸시 페이로드 직렬화 실패. roomId={}, messageId={}", payload.roomId(),
          payload.messageId(), e);
      return null;
    }
  }
}
