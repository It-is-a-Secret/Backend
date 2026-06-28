package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatRoomNotificationType;
import com.blursome.blursome.chat.event.ChatRoomNotificationEvent;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.chat.service.RoomOpenResult;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 페어당 방 1개·CLOSED 영구성(이슈 #87)을 실제 MySQL(Testcontainers)로 검증한다.
 *
 * <p>게이트(피드 5장 READY·이성 등)는 {@code ChatStartServiceTest}가 단위로 검증하므로, 여기서는 방 개설
 * 분기({@link ChatRoomService#openRoom})와 {@code pair_key} 유니크 제약의 DB 레벨 불변식에 집중한다 —
 * 첫 접촉 생성 → 재호출 시 ACTIVE 흡수 → 종료 후 영구 거절({@code RELATIONSHIP_CLOSED}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class ChatStartRelationshipIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private ChatRoomService chatRoomService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatMessageRepository chatMessageRepository;

  @Test
  @DisplayName("첫 접촉이면 방+첫 메시지를 한 단위로 만들고, 재호출이면 같은 방을 메시지 없이 created=false로 흡수한다")
  void openRoom_firstContactThenReuse() {
    Member a = saveMember("a");
    Member b = saveMember("b");

    RoomOpenResult first = chatRoomService.openRoom(a.getId(), b.getId(), text("첫 메시지"));
    assertThat(first.created()).isTrue();
    assertThat(first.room().getRoomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    // 방+첫 메시지가 원자적으로 저장된다.
    assertThat(first.firstMessage()).isNotNull();
    assertThat(chatMessageRepository.findById(first.firstMessage().messageId())).isPresent();

    RoomOpenResult second = chatRoomService.openRoom(a.getId(), b.getId(), text("두 번째 시도"));
    assertThat(second.created()).isFalse();
    assertThat(second.room().getId()).isEqualTo(first.room().getId());
    assertThat(second.firstMessage()).isNull();
  }

  @Test
  @DisplayName("관계가 종료(CLOSED)되면 같은 페어는 RELATIONSHIP_CLOSED로 영구 거절되고 방은 단건으로 유지된다")
  void openRoom_afterCloseIsPermanentlyRejected() {
    Member a = saveMember("a");
    Member b = saveMember("b");
    Long roomId = chatRoomService.openRoom(a.getId(), b.getId(), text("첫 메시지")).room().getId();

    // 한쪽이 나가면 1:1이므로 방이 CLOSED 된다(pairKey는 비워지지 않음).
    chatRoomService.leaveRoom(roomId, a.getId());
    assertThat(chatRoomRepository.findById(roomId)).get()
        .extracting("roomStatus").isEqualTo(ChatRoomStatus.CLOSED);

    // 같은 페어 재시작 시도 → 영구 거절. 새 방을 만들지 않는다.
    assertThatThrownBy(() -> chatRoomService.openRoom(a.getId(), b.getId(), text("재시도")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.RELATIONSHIP_CLOSED.getCode());
    assertThatThrownBy(() -> chatRoomService.openRoom(b.getId(), a.getId(), text("재시도")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.RELATIONSHIP_CLOSED.getCode());
  }

  @Test
  @DisplayName("첫 접촉으로 방을 만들면 상대 개인 큐 대상 NEW_ROOM 알림을 1건 발행하고, 재호출(ACTIVE 흡수)에선 추가 발행하지 않는다")
  void openRoom_firstContact_publishesNewRoomNotificationToPartner(ApplicationEvents events) {
    Member a = saveMember("a");
    Member b = saveMember("b");

    RoomOpenResult first = chatRoomService.openRoom(a.getId(), b.getId(), text("첫 메시지"));

    // 개설자(a)가 아니라 수신자(b)의 개인 큐로, 개설자 정보·첫 메시지 미리보기를 실어 NEW_ROOM이 발행된다.
    List<ChatRoomNotificationEvent> newRoomEvents = newRoomNotifications(events);
    assertThat(newRoomEvents).hasSize(1);
    ChatRoomNotificationEvent event = newRoomEvents.get(0);
    assertThat(event.targetMemberId()).isEqualTo(b.getId());
    assertThat(event.notification().roomId()).isEqualTo(first.room().getId());
    assertThat(event.notification().partnerId()).isEqualTo(a.getId());
    assertThat(event.notification().partnerNickname()).isEqualTo(a.getNickName());
    assertThat(event.notification().message().messageId())
        .isEqualTo(first.firstMessage().messageId());

    // 같은 페어 재호출은 ACTIVE 방을 메시지 없이 흡수하므로 새 NEW_ROOM이 더 생기지 않는다.
    chatRoomService.openRoom(a.getId(), b.getId(), text("두 번째 시도"));
    assertThat(newRoomNotifications(events)).hasSize(1);
  }

  private List<ChatRoomNotificationEvent> newRoomNotifications(ApplicationEvents events) {
    return events.stream(ChatRoomNotificationEvent.class)
        .filter(e -> e.notification().type() == ChatRoomNotificationType.NEW_ROOM)
        .toList();
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
