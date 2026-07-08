package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.block.service.BlockService;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * v1 block policy (#77): blocker-only read hiding, bidirectional send blocking.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatBlockScenarioIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private BlockService blockService;

  @Autowired
  private ChatRoomService chatRoomService;

  @Autowired
  private ChatMessageService chatMessageService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Test
  @DisplayName("A가 B를 차단하면 A에게는 목록·단건·이력이 숨겨진다(#77 v1)")
  void whenABlocksB_blockerCannotReadRoom() {
    Fixture f = persistRoomWithMessage();

    blockService.block(f.aId(), f.bId());

    assertThat(chatRoomService.getMyRooms(f.aId())).isEmpty();
    assertThatThrownBy(() -> chatRoomService.getRoom(f.roomId(), f.aId()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
    assertThatThrownBy(() -> chatRoomService.getMessageHistory(f.roomId(), f.aId(), null, 30))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("A가 B를 차단해도 B는 목록·단건·이력을 볼 수 있고 송신은 막힌다(#77 v1)")
  void whenABlocksB_blockedMemberCanReadButCannotSend() {
    Fixture f = persistRoomWithMessage();

    blockService.block(f.aId(), f.bId());

    ChatRoomSummaryResponse summary = onlyRoomOf(f.bId(), f.roomId());
    assertThat(summary.roomStatus()).isEqualTo(ChatRoomStatus.BLOCKED);
    assertThat(summary.partnerNickname()).isEqualTo(f.aNick());
    assertThat(chatRoomService.getRoom(f.roomId(), f.bId()).roomId()).isEqualTo(f.roomId());
    assertThat(chatRoomService.getMessageHistory(f.roomId(), f.bId(), null, 30)).hasSize(1);
    assertThatThrownBy(() -> chatMessageService.send(f.roomId(), f.bId(), text("blocked send")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
  }

  private ChatRoomSummaryResponse onlyRoomOf(Long memberId, Long roomId) {
    return chatRoomService.getMyRooms(memberId).stream()
        .filter(r -> r.roomId().equals(roomId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("room not found in list: " + roomId));
  }

  private Fixture persistRoomWithMessage() {
    Member a = saveMember("a");
    Member b = saveMember("b");
    ChatRoom room = chatRoomRepository.save(ChatRoom.createOnMatched(a.getId(), b.getId()));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, a));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, b));
    chatMessageService.send(room.getId(), a.getId(), text("hello before block"));
    return new Fixture(room.getId(), a.getId(), b.getId(), a.getNickName());
  }

  private Member saveMember(String tag) {
    String unique = tag + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null);
    ReflectionTestUtils.setField(member, "nickName", "nick-" + unique);
    return memberRepository.save(member);
  }

  private ChatMessageSendRequest text(String content) {
    return new ChatMessageSendRequest(ChatMessageType.TEXT, content);
  }

  private record Fixture(Long roomId, Long aId, Long bId, String aNick) {
  }
}
