package com.blursome.blursome.chat.controller;

import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.request.ChatReadRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.exception.code.GlobalErrorCode;
import com.blursome.blursome.global.response.ErrorResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * 실시간 메시지 송신·읽음 처리 STOMP 컨트롤러(설계 §6-2, §7-3, §7-4).
 *
 * <p>아키텍처 규칙에 따라 영속·검증은 {@link ChatMessageService}(Facade)가 담당하고, 컨트롤러는 메시지 변환·발행만 한다.
 * 발신자는 클라이언트 입력이 아니라 STOMP principal({@code memberId})에서 추출한다(설계 §9 발신자 위조 방지).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {

  private final ChatMessageService chatMessageService;
  private final SimpMessagingTemplate messagingTemplate;

  /**
   * {@code /app/rooms/{roomId}/send} — 메시지를 저장한 뒤 {@code /topic/rooms/{roomId}} 구독자에게 브로드캐스트한다.
   */
  @MessageMapping("/rooms/{roomId}/send")
  public void send(
      @DestinationVariable Long roomId,
      @Valid @Payload ChatMessageSendRequest request,
      Principal principal
  ) {
    ChatMessageResponse response = chatMessageService.send(roomId, memberId(principal), request);
    messagingTemplate.convertAndSend("/topic/rooms/" + roomId, response);
  }

  /** {@code /app/rooms/{roomId}/read} — 읽음 위치를 전진시킨다(WebSocket 우선). */
  @MessageMapping("/rooms/{roomId}/read")
  public void read(
      @DestinationVariable Long roomId,
      @Valid @Payload ChatReadRequest request,
      Principal principal
  ) {
    chatMessageService.markAsRead(roomId, memberId(principal), request.lastReadMessageId());
  }

  /**
   * STOMP 처리 중 도메인 검증 실패를 발신자 개인 큐로 통지한다(설계 §6-2, §9).
   * {@code broadcast = false}이므로 오류를 일으킨 세션의 사용자에게만 전달된다.
   */
  @MessageExceptionHandler(BaseException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorResponse handleBaseException(BaseException e) {
    return ErrorResponse.of(e.getHttpStatus(), e.getMessage(), e.getCode());
  }

  /**
   * 잘못된 페이로드({@code @Valid} 위반·JSON 변환 실패·잘못된 enum 등)를 개인 오류 큐로 통지한다.
   * 모두 클라이언트 입력 오류이므로 {@code INVALID_MESSAGE}(400)로 통일한다. 클라이언트가 고칠 수 있는 오류라 로그는 남기지 않는다.
   */
  @MessageExceptionHandler({MethodArgumentNotValidException.class, MessageConversionException.class})
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorResponse handleInvalidPayload(Exception e) {
    return ErrorResponse.from(ChatErrorCode.INVALID_MESSAGE);
  }

  /**
   * 그 밖의 예기치 못한 예외(DB 장애·코드 결함 등)는 내부 오류로 기록하고 500으로 응답한다(설계 §6-2).
   * 잘못된 입력으로 은폐하지 않으며, {@code ERROR} 프레임 누수로 연결이 끊기지 않도록 개인 큐로만 통지한다.
   * {@link BaseException}과 위 입력 오류 핸들러가 더 구체적이므로 그쪽이 우선 처리된다.
   */
  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorResponse handleUnexpected(Exception e) {
    log.error("STOMP 메시지 처리 중 예기치 못한 오류", e);
    return ErrorResponse.from(GlobalErrorCode.INTERNAL_SERVER_ERROR);
  }

  /** CONNECT 때 심어 둔 principal에서 발신 회원 id를 추출한다(인터셉터가 인증을 보장). */
  private Long memberId(Principal principal) {
    return Long.valueOf(principal.getName());
  }
}
