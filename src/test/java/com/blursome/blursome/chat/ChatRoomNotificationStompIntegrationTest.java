package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 유저 단위 알림 토픽의 <b>방 미구독 수신</b>을 실제 STOMP(WebSocket)로 종단 검증한다(이슈 #88).
 *
 * <p>핵심 계약: 회원이 {@code /topic/rooms/{roomId}}를 구독하지 않고 개인 큐({@code /user/queue/rooms})만
 * 구독해도, 새 대화 시작({@code NEW_ROOM})·새 메시지({@code NEW_MESSAGE})를 받는다. 발행 트리거는 서비스
 * (커밋 후 {@code AFTER_COMMIT} 리스너)가 담당하므로, 발신자 세션을 따로 띄우지 않고 서비스를 직접 호출해
 * 커밋 → 리스너 → 브로커 → 수신자 WebSocket 세션의 전 구간을 통과시킨다.
 *
 * <p>남은 체크리스트 두 가지는 후속 보강 대상이다(follow-up, 이슈 #88):
 * <ul>
 *   <li><b>타인 큐 구독 차단(격리)</b>: {@code /user/**}는 Spring이 세션 principal(=memberId)로 라우팅해
 *       타인 큐를 가리킬 수 없다(프레임워크 보장). 회귀 방지로 "제3자는 A→B 알림을 받지 못한다" 형태의 격리
 *       테스트를 추가할 수 있다.</li>
 *   <li><b>다중 세션</b>: 같은 회원이 두 세션을 열면 양쪽 모두 같은 알림을 받는지(다중 디바이스) 검증.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatRoomNotificationStompIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final long AWAIT_SECONDS = 5;
  // SUBSCRIBE는 ack가 없어 브로커 등록 전에 발행하면 누락될 수 있다. 발행 트리거 전 짧게 정착 시간을 둔다.
  private static final long SUBSCRIBE_SETTLE_MILLIS = 500;

  @LocalServerPort
  private int port;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private ChatRoomService chatRoomService;

  @Autowired
  private ChatMessageService chatMessageService;

  @Autowired
  private JwtTokenProvider jwtTokenProvider;

  @Autowired
  private ObjectMapper objectMapper;

  private WebSocketStompClient stompClient;

  @BeforeEach
  void setUp() {
    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
  }

  @Test
  @DisplayName("방 토픽을 구독하지 않고 개인 큐만 구독한 수신자도 새 메시지(NEW_MESSAGE)를 받는다")
  void roomUnsubscribedRecipient_receivesNewMessage() throws Exception {
    Member sender = saveMember("a");
    Member recipient = saveMember("b");
    Long roomId =
        chatRoomService.openRoom(sender.getId(), recipient.getId(), text("첫 메시지")).room().getId();

    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    StompSession session = connect(recipient.getId());
    // 방 토픽(/topic/rooms/{id})은 일부러 구독하지 않는다 — 개인 큐만으로 수신되는지가 핵심.
    session.subscribe("/user/queue/rooms", collectingHandler(received));
    Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

    // 발신자 세션 없이 서비스 직접 호출 → 커밋 후 리스너가 수신자 개인 큐로 NEW_MESSAGE 발행.
    chatMessageService.send(roomId, sender.getId(), text("방 밖에서도 오는 메시지"));

    JsonNode notification = pollForType(received, "NEW_MESSAGE");
    assertThat(notification).as("개인 큐로 NEW_MESSAGE 수신").isNotNull();
    assertThat(notification.get("roomId").asLong()).isEqualTo(roomId);
    assertThat(notification.get("partnerId").asLong()).isEqualTo(sender.getId());
    assertThat(notification.get("message").get("senderId").asLong()).isEqualTo(sender.getId());
    assertThat(notification.get("message").get("content").asText()).isEqualTo("방 밖에서도 오는 메시지");

    session.disconnect();
  }

  @Test
  @DisplayName("첫 접촉으로 방이 생기면, 그 방을 구독한 적 없는 수신자도 개인 큐로 NEW_ROOM을 받는다")
  void roomUnsubscribedRecipient_receivesNewRoomOnFirstContact() throws Exception {
    Member initiator = saveMember("a");
    Member recipient = saveMember("b");

    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    StompSession session = connect(recipient.getId());
    session.subscribe("/user/queue/rooms", collectingHandler(received));
    Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

    // 첫 접촉 → 새 방 개설. 수신자는 이 방을 구독한 적이 없다.
    Long roomId =
        chatRoomService.openRoom(initiator.getId(), recipient.getId(), text("안녕하세요")).room().getId();

    // 첫 메시지에 대해 NEW_ROOM과 NEW_MESSAGE가 모두 올 수 있으므로 NEW_ROOM을 만날 때까지 수집한다.
    JsonNode newRoom = pollForType(received, "NEW_ROOM");
    assertThat(newRoom).as("개인 큐로 NEW_ROOM 수신").isNotNull();
    assertThat(newRoom.get("roomId").asLong()).isEqualTo(roomId);
    assertThat(newRoom.get("partnerId").asLong()).isEqualTo(initiator.getId());
    assertThat(newRoom.get("partnerNickname").asText()).isEqualTo(initiator.getNickName());
    assertThat(newRoom.get("message").get("content").asText()).isEqualTo("안녕하세요");

    session.disconnect();
  }

  /** CONNECT 네이티브 헤더에 Bearer AT를 실어 STOMP 세션을 연다(인터셉터가 이 토큰으로 principal=memberId 설정). */
  private StompSession connect(Long memberId) throws Exception {
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add(
        "Authorization", "Bearer " + jwtTokenProvider.issueAccessToken(memberId, MemberRole.USER));
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    // WebSocketConfig의 허용 Origin 패턴(localhost:5173)을 만족시켜 핸드셰이크를 통과시킨다.
    handshakeHeaders.setOrigin("http://localhost:5173");
    return stompClient.connectAsync(
            "ws://localhost:" + port + "/ws",
            handshakeHeaders,
            connectHeaders,
            new StompSessionHandlerAdapter() {})
        .get(AWAIT_SECONDS, TimeUnit.SECONDS);
  }

  /** 수신 프레임을 byte[]로 받아 JSON 트리로 파싱해 큐에 쌓는 핸들러(앱 ObjectMapper로 LocalDateTime 등 처리). */
  private StompFrameHandler collectingHandler(BlockingQueue<JsonNode> sink) {
    return new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        try {
          sink.add(objectMapper.readTree((byte[]) payload));
        } catch (Exception e) {
          throw new IllegalStateException("알림 페이로드 파싱 실패", e);
        }
      }
    };
  }

  /** 지정한 {@code type}의 알림을 제한 시간 안에서 기다린다. 다른 타입은 건너뛰고, 시간이 지나면 null. */
  private JsonNode pollForType(BlockingQueue<JsonNode> sink, String type) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
    long remaining;
    while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
      JsonNode node = sink.poll(remaining, TimeUnit.NANOSECONDS);
      if (node == null) {
        return null;
      }
      if (type.equals(node.get("type").asText())) {
        return node;
      }
    }
    return null;
  }

  private ChatMessageSendRequest text(String content) {
    return new ChatMessageSendRequest(ChatMessageType.TEXT, content);
  }

  private Member saveMember(String tag) {
    String unique = tag + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null);
    ReflectionTestUtils.setField(member, "nickName", "닉네임-" + unique);
    return memberRepository.save(member);
  }
}
