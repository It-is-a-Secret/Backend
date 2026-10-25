package com.blursome.blursome.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomMembershipReader;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.response.ErrorResponse;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.global.security.MemberAccessValidator;
import com.blursome.blursome.member.exception.MemberErrorCode;
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
  private MemberAccessValidator memberAccessValidator;

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
            memberAccessValidator, messagingTemplateProvider);
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
            memberAccessValidator, messagingTemplateProvider);
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

  @Test
  @DisplayName("탈퇴 회원의 유효한 토큰으로 CONNECT하면 연결을 거절한다(#107)")
  void preSend_connect_whenWithdrawnMember_thenRejects() {
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(jwtTokenProvider, membershipReader,
            memberAccessValidator, messagingTemplateProvider);
    given(jwtTokenProvider.parseAccess("valid-token"))
        .willReturn(JwtAuthentication.of(MEMBER_ID, MemberRole.USER));
    willThrow(BaseException.from(MemberErrorCode.MEMBER_INACTIVE))
        .given(memberAccessValidator).validateAccess(MEMBER_ID);

    assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer valid-token"), channel))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_403_INACTIVE");
  }

  @Test
  @DisplayName("활성 회원의 토큰으로 CONNECT하면 principal을 세션에 심는다")
  void preSend_connect_whenActiveMember_thenSetsPrincipal() {
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(jwtTokenProvider, membershipReader,
            memberAccessValidator, messagingTemplateProvider);
    JwtAuthentication authentication = JwtAuthentication.of(MEMBER_ID, MemberRole.USER);
    given(jwtTokenProvider.parseAccess("valid-token")).willReturn(authentication);
    Message<?> message = connectMessage("Bearer valid-token");

    interceptor.preSend(message, channel);

    verify(memberAccessValidator).validateAccess(MEMBER_ID);
    assertThat(StompHeaderAccessor.wrap(message).getUser()).isSameAs(authentication);
  }

  @Test
  @DisplayName("탈퇴 전에 연결한 세션이 탈퇴 후 방 토픽을 구독하면 개인 오류를 보내고 차단한다(#107)")
  void preSend_subscribe_whenWithdrawnMember_thenDrops() {
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(jwtTokenProvider, membershipReader,
            memberAccessValidator, messagingTemplateProvider);
    willThrow(BaseException.from(MemberErrorCode.MEMBER_INACTIVE))
        .given(memberAccessValidator).validateAccess(MEMBER_ID);
    given(messagingTemplateProvider.getObject()).willReturn(messagingTemplate);

    Message<?> result = interceptor.preSend(subscribeMessage(MEMBER_ID), channel);

    assertThat(result).isNull();
    verify(membershipReader, never()).getVisibleMembership(any(), any());
    verify(messagingTemplate).convertAndSendToUser(
        eq(String.valueOf(MEMBER_ID)),
        eq("/queue/errors"),
        argThat(payload -> ((ErrorResponse) payload).getCode().equals("MEMBER_403_INACTIVE")),
        anyMap());
  }

  private Message<?> connectMessage(String authorization) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", authorization);
    accessor.setSessionId(SESSION_ID);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
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
