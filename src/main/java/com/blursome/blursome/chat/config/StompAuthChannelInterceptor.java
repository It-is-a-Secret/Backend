package com.blursome.blursome.chat.config;

import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomMembershipReader;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.exception.JwtAuthenticationException;
import com.blursome.blursome.global.exception.code.JwtErrorCode;
import com.blursome.blursome.global.response.ErrorResponse;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtTokenProvider;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 인바운드 프레임을 가로채 인증·구독 권한을 검증한다(설계 §6-3, §7-2, §9).
 *
 * <ul>
 *   <li>{@code CONNECT}: {@code Authorization: Bearer <AT>} 헤더의 토큰을 {@link JwtTokenProvider}로 검증하고,
 *       복원한 {@link JwtAuthentication}을 STOMP 세션 principal로 저장한다. 이후 모든 프레임의 발신자는 이 principal로
 *       식별한다(클라이언트가 보낸 senderId 미신뢰).</li>
 *   <li>{@code SUBSCRIBE}: {@code /topic/rooms/{roomId}} 구독 시 해당 회원이 그 방의 활성 참여자인지 검증해 비참여자
 *       구독을 차단한다.</li>
 * </ul>
 *
 * <p>{@code CONNECT} 인증 실패는 예외로 연결을 거절한다. 반면 {@code SUBSCRIBE} 권한 실패는 연결 전체를 끊지 않고
 * 개인 오류 큐({@code /user/queue/errors})로 통지한 뒤 해당 프레임만 드롭한다(설계 §6-2·§9, {@code preSend}가 null을
 * 반환하면 그 메시지는 브로커로 전달되지 않아 구독이 성립하지 않는다).
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/rooms/(\\d+)$");
  private static final String ERROR_DESTINATION = "/queue/errors";

  private final JwtTokenProvider jwtTokenProvider;
  private final ChatRoomMembershipReader membershipReader;
  // SimpMessagingTemplate은 브로커 설정이 만들고, 그 설정은 이 인터셉터가 등록되는 인바운드 채널 구성에 의존한다.
  // 생성자에서 바로 주입하면 순환 의존이 되므로 ObjectProvider로 사용 시점에 지연 해석한다.
  private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      authenticate(accessor);
    } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      return validateSubscription(accessor, message);
    }
    return message;
  }

  /** CONNECT 프레임의 Bearer 토큰을 검증해 principal을 세션에 심는다. 실패 시 연결 거절. */
  private void authenticate(StompHeaderAccessor accessor) {
    String token = resolveToken(accessor);
    JwtAuthentication authentication = jwtTokenProvider.parseAccess(token);
    accessor.setUser(authentication);
  }

  private String resolveToken(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      throw new JwtAuthenticationException(JwtErrorCode.UNAUTHORIZED);
    }
    return header.substring(BEARER_PREFIX.length());
  }

  /**
   * {@code /topic/rooms/{roomId}} 구독자가 그 방의 활성 참여자인지 검증한다(비참여자 차단).
   * 통과하면 원본 메시지를 그대로 흘려보내고, 막히면 개인 오류 큐로 통지한 뒤 {@code null}을 반환해 구독을 차단한다.
   */
  private Message<?> validateSubscription(StompHeaderAccessor accessor, Message<?> message) {
    String destination = accessor.getDestination();
    if (destination == null) {
      return message;
    }
    Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
    if (!matcher.matches()) {
      // 방 토픽이 아닌 구독(예: /user/queue/errors)은 검증 대상이 아니다.
      return message;
    }
    Long roomId = Long.valueOf(matcher.group(1));
    Long memberId = currentMemberId(accessor);
    try {
      membershipReader.getVisibleMembership(roomId, memberId);
      return message;
    } catch (BaseException e) {
      sendError(accessor.getUser(), accessor.getSessionId(), e);
      return null;
    }
  }

  /**
   * 구독 거부 사유를 지금 SUBSCRIBE를 보낸 그 세션에만 통지한다(연결은 유지).
   * {@code simpSessionId}를 헤더에 담지 않으면 같은 계정의 다른 WebSocket 세션까지 오류를 받으므로, 세션 id를 명시해
   * 원본 세션으로만 전달한다.
   */
  private void sendError(Principal principal, String sessionId, BaseException e) {
    SimpMessageHeaderAccessor headerAccessor =
        SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headerAccessor.setSessionId(sessionId);
    headerAccessor.setLeaveMutable(true);
    messagingTemplate.getObject().convertAndSendToUser(
        principal.getName(),
        ERROR_DESTINATION,
        ErrorResponse.of(e.getHttpStatus(), e.getMessage(), e.getCode()),
        headerAccessor.getMessageHeaders());
  }

  /** CONNECT 때 심어 둔 principal에서 회원 id를 꺼낸다. principal이 없으면 인증되지 않은 세션이다. */
  private Long currentMemberId(StompHeaderAccessor accessor) {
    Principal principal = accessor.getUser();
    if (principal == null) {
      throw new JwtAuthenticationException(JwtErrorCode.UNAUTHORIZED);
    }
    try {
      return Long.valueOf(principal.getName());
    } catch (NumberFormatException e) {
      throw BaseException.from(ChatErrorCode.NOT_PARTICIPANT);
    }
  }
}
