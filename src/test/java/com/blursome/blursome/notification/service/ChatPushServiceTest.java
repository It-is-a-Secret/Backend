package com.blursome.blursome.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.member.service.MemberService;
import com.blursome.blursome.notification.dto.PushTarget;
import com.blursome.blursome.notification.sender.WebPushSender;
import com.blursome.blursome.notification.sender.WebPushSender.PushResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatPushServiceTest {

  private static final Long ROOM_ID = 12L;
  private static final Long SENDER_ID = 100L;
  private static final Long RECIPIENT_ID = 200L;
  private static final Long MESSAGE_ID = 345L;

  @Mock
  private PushSubscriptionService pushSubscriptionService;
  @Mock
  private ChatRoomService chatRoomService;
  @Mock
  private MemberService memberService;
  @Mock
  private WebPushSender webPushSender;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ChatPushService chatPushService;

  private final PushTarget phone = new PushTarget(1L, "https://web.push.apple.com/a", "k", "a");
  private final PushTarget laptop = new PushTarget(2L, "https://fcm.googleapis.com/b", "k", "a");

  @BeforeEach
  void setUp() {
    chatPushService = new ChatPushService(pushSubscriptionService, chatRoomService, memberService,
        webPushSender, objectMapper);
    given(webPushSender.isEnabled()).willReturn(true);
    given(pushSubscriptionService.findTargets(RECIPIENT_ID)).willReturn(List.of(phone, laptop));
    given(chatRoomService.canReceiveMessagePush(ROOM_ID, RECIPIENT_ID)).willReturn(true);
    given(memberService.findNickName(SENDER_ID)).willReturn(Optional.of("블러곰"));
  }

  @Test
  @DisplayName("수신자 모든 기기로 닉네임·미리보기·/chat/{roomId}를 담아 보내고 결과를 반영한다")
  void sendNewMessage_whenEligible_thenSendsToAllDevicesAndRecords() throws Exception {
    // given
    given(webPushSender.send(eq(phone), any(), eq("room-12"))).willReturn(PushResult.DELIVERED);
    given(webPushSender.send(eq(laptop), any(), eq("room-12"))).willReturn(PushResult.EXPIRED);

    // when
    chatPushService.sendNewMessage(RECIPIENT_ID, SENDER_ID, message(ChatMessageType.TEXT, "안녕"));

    // then
    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(webPushSender).send(eq(phone), payload.capture(), eq("room-12"));
    JsonNode json = objectMapper.readTree(payload.getValue());
    assertThat(json.get("type").asText()).isEqualTo("NEW_MESSAGE");
    assertThat(json.get("roomId").asLong()).isEqualTo(ROOM_ID);
    assertThat(json.get("messageId").asLong()).isEqualTo(MESSAGE_ID);
    assertThat(json.get("title").asText()).isEqualTo("블러곰");
    assertThat(json.get("body").asText()).isEqualTo("안녕");
    assertThat(json.get("url").asText()).isEqualTo("/chat/12");
    verify(pushSubscriptionService).recordResults(List.of(1L), List.of(2L));
  }

  @Test
  @DisplayName("SYSTEM 메시지는 보내지 않는다")
  void sendNewMessage_whenSystem_thenSkips() {
    chatPushService.sendNewMessage(RECIPIENT_ID, SENDER_ID, message(ChatMessageType.SYSTEM, "안내"));

    verify(webPushSender, never()).send(any(), any(), any());
    verifyNoInteractions(pushSubscriptionService);
  }

  @Test
  @DisplayName("발신자 본인에게는 보내지 않는다")
  void sendNewMessage_whenRecipientIsSender_thenSkips() {
    chatPushService.sendNewMessage(SENDER_ID, SENDER_ID, message(ChatMessageType.TEXT, "안녕"));

    verify(webPushSender, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("수신자가 나갔거나 방이 ACTIVE가 아니면(차단·신고·종료) 보내지 않는다")
  void sendNewMessage_whenRoomNotDeliverable_thenSkips() {
    given(chatRoomService.canReceiveMessagePush(ROOM_ID, RECIPIENT_ID)).willReturn(false);

    chatPushService.sendNewMessage(RECIPIENT_ID, SENDER_ID, message(ChatMessageType.TEXT, "안녕"));

    verify(webPushSender, never()).send(any(), any(), any());
    verify(pushSubscriptionService, never()).recordResults(any(), any());
  }

  @Test
  @DisplayName("수신 기기가 없으면 방 상태 조회도 하지 않는다")
  void sendNewMessage_whenNoDevices_thenSkipsEarly() {
    given(pushSubscriptionService.findTargets(RECIPIENT_ID)).willReturn(List.of());

    chatPushService.sendNewMessage(RECIPIENT_ID, SENDER_ID, message(ChatMessageType.TEXT, "안녕"));

    verify(chatRoomService, never()).canReceiveMessagePush(anyLong(), anyLong());
    verify(webPushSender, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("푸시가 설정되지 않았으면 아무것도 조회하지 않는다")
  void sendNewMessage_whenDisabled_thenSkips() {
    given(webPushSender.isEnabled()).willReturn(false);

    chatPushService.sendNewMessage(RECIPIENT_ID, SENDER_ID, message(ChatMessageType.TEXT, "안녕"));

    verifyNoInteractions(pushSubscriptionService, chatRoomService, memberService);
  }

  private static ChatMessageResponse message(ChatMessageType type, String content) {
    return new ChatMessageResponse(MESSAGE_ID, ROOM_ID, type == ChatMessageType.SYSTEM ? null : SENDER_ID,
        type, content, LocalDateTime.now());
  }
}
