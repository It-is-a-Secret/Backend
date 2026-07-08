package com.blursome.blursome.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomMembershipReader;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.response.ErrorResponse;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

  private static final Long ROOM_ID = 10L;
  private static final Long MEMBER_ID = 100L;
  private static final String SESSION_ID = "session-1";

  @Mock
  private JwtTokenProvider jwtTokenProvider;

  @Mock
  private ChatRoomMembershipReader membershipReader;

  @Mock
  private ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;

  @Mock
  private SimpMessagingTemplate messagingTemplate;

  @Mock
  private MessageChannel channel;

  @Test
  @DisplayName("피차단자처럼 조회 가능한 참여자의 방 구독은 허용한다(#77 v1)")
  void preSend_subscribe_whenVisible_thenAllows() {
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(jwtTokenProvider, membershipReader,
            messagingTemplateProvider);
    Message<?> message = subscribeMessage(MEMBER_ID);

    Message<?> result = interceptor.preSend(message, channel);

    assertThat(result).isSameAs(message);
    verify(membershipReader).getVisibleMembership(ROOM_ID, MEMBER_ID);
    verify(messagingTemplateProvider, never()).getObject();
  }

  @Test
  @DisplayName("차단자처럼 조회 불가한 참여자의 방 구독은 개인 오류를 보내고 차단한다(#77 v1)")
  void preSend_subscribe_whenHiddenFromViewer_thenDrops() {
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(jwtTokenProvider, membershipReader,
            messagingTemplateProvider);
    Message<?> message = subscribeMessage(MEMBER_ID);
    given(membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .willThrow(BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    given(messagingTemplateProvider.getObject()).willReturn(messagingTemplate);

    Message<?> result = interceptor.preSend(message, channel);

    assertThat(result).isNull();
    verify(messagingTemplate).convertAndSendToUser(
        eq(String.valueOf(MEMBER_ID)),
        eq("/queue/errors"),
        argThat(payload -> ((ErrorResponse) payload).getCode()
            .equals(ChatErrorCode.ROOM_NOT_FOUND.getCode())),
        anyMap());
  }

  private Message<?> subscribeMessage(Long memberId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination("/topic/rooms/" + ROOM_ID);
    accessor.setSessionId(SESSION_ID);
    accessor.setUser(JwtAuthentication.of(memberId, MemberRole.USER));
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
