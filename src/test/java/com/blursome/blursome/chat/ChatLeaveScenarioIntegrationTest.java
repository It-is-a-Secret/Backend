package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 채팅방 비대칭 종료(설계 §7-6)를 실제 MySQL(Testcontainers)로 검증한다.
 *
 * <p>한쪽(A)이 나가면 방은 {@code CLOSED}가 되지만, <b>나간 A만</b> 즉시 목록·조회·송신이 막히고
 * <b>남은 B</b>는 직접 나갈 때까지 목록·이력을 그대로 볼 수 있다(송신만 막힘). 서비스 계층의 조회/쓰기
 * 가시성 분기·목록 안읽음 집계가 이 정책과 일치하는지 종단 간으로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatLeaveScenarioIntegrationTest {

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
  @DisplayName("A가 나가면 A 목록에서 제외되고 단건·이력 조회는 ROOM_NOT_FOUND, 송신은 ROOM_CLOSED로 차단된다")
  void afterALeaves_aIsExcludedAndBlocked() {
    Fixture f = persistRoom("닉네임A", "닉네임B");
    chatMessageService.send(f.roomId(), f.aId(), text("안녕"));

    chatRoomService.leaveRoom(f.roomId(), f.aId());

    assertThat(chatRoomService.getMyRooms(f.aId())).isEmpty();
    assertThatThrownBy(() -> chatRoomService.getRoom(f.roomId(), f.aId()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
    assertThatThrownBy(() -> chatRoomService.getMessageHistory(f.roomId(), f.aId(), null, 30))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
    // 나간 A 본인도 새 메시지 전송 불가(방이 종료됐고 본인도 leftAt → ROOM_CLOSED).
    assertThatThrownBy(() -> chatMessageService.send(f.roomId(), f.aId(), text("나갔지만 전송")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
  }

  @Test
  @DisplayName("A가 나가도 B 목록에는 방이 남고 상대(A) 닉네임·진행됐던 단계가 보이며 단건·이력 조회가 가능하다")
  void afterALeaves_bKeepsRoomWithPartnerInfoAndProgress() {
    Fixture f = persistRoom("닉네임A", "닉네임B");
    // 양쪽 동의로 단계를 STEP_1까지 올려 둔다(진행됐던 단계 확인용)
    chatRoomService.agreeProgress(f.roomId(), f.aId());
    chatRoomService.agreeProgress(f.roomId(), f.bId());
    chatMessageService.send(f.roomId(), f.aId(), text("마지막 메시지"));

    chatRoomService.leaveRoom(f.roomId(), f.aId());

    ChatRoomSummaryResponse summary = onlyRoomOf(f.bId(), f.roomId());
    assertThat(summary.roomStatus()).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(summary.partnerNickname()).isEqualTo("닉네임A");
    assertThat(summary.progressStatus()).isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);

    // 단건·이력 조회 가능
    assertThat(chatRoomService.getRoom(f.roomId(), f.bId()).roomId()).isEqualTo(f.roomId());
    assertThat(chatRoomService.getMessageHistory(f.roomId(), f.bId(), null, 30)).hasSize(1);
  }

  @Test
  @DisplayName("A가 나간 뒤 B는 송신·읽음·단계 동의가 모두 ROOM_CLOSED로 차단된다")
  void afterALeaves_bCannotSendReadOrAgree() {
    Fixture f = persistRoom("닉네임A", "닉네임B");
    ChatMessageResponse sent = chatMessageService.send(f.roomId(), f.aId(), text("안녕"));

    chatRoomService.leaveRoom(f.roomId(), f.aId());

    assertThatThrownBy(() -> chatMessageService.send(f.roomId(), f.bId(), text("답장")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
    assertThatThrownBy(() -> chatMessageService.markAsRead(f.roomId(), f.bId(), sent.messageId()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
    assertThatThrownBy(() -> chatRoomService.agreeProgress(f.roomId(), f.bId()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
  }

  @Test
  @DisplayName("상대가 먼저 나가 종료된 방에서도 B가 직접 나가면 B 목록에서도 사라진다")
  void afterBothLeave_bIsAlsoExcluded() {
    Fixture f = persistRoom("닉네임A", "닉네임B");
    chatRoomService.leaveRoom(f.roomId(), f.aId());
    assertThat(chatRoomService.getMyRooms(f.bId())).hasSize(1);

    chatRoomService.leaveRoom(f.roomId(), f.bId());

    assertThat(chatRoomService.getMyRooms(f.bId())).isEmpty();
  }

  @Test
  @DisplayName("종료된 방의 안읽음 수는 목록과 단건 조회가 일치한다")
  void closedRoomUnreadCount_listMatchesSingle() {
    Fixture f = persistRoom("닉네임A", "닉네임B");
    chatMessageService.send(f.roomId(), f.aId(), text("1"));
    chatMessageService.send(f.roomId(), f.aId(), text("2"));
    chatMessageService.send(f.roomId(), f.aId(), text("3"));

    chatRoomService.leaveRoom(f.roomId(), f.aId());

    long single = chatRoomService.getRoom(f.roomId(), f.bId()).unreadCount();
    long list = onlyRoomOf(f.bId(), f.roomId()).unreadCount();
    assertThat(single).isEqualTo(3L);
    assertThat(list).isEqualTo(single);
  }

  // ---------- helpers ----------

  /** B의 목록에서 해당 방 요약 한 건을 꺼낸다(목록에 그 방이 있어야 통과). */
  private ChatRoomSummaryResponse onlyRoomOf(Long memberId, Long roomId) {
    return chatRoomService.getMyRooms(memberId).stream()
        .filter(r -> r.roomId().equals(roomId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("목록에 방이 없습니다: " + roomId));
  }

  private ChatMessageSendRequest text(String content) {
    return new ChatMessageSendRequest(ChatMessageType.TEXT, content);
  }

  /** 두 회원과 그 사이의 ACTIVE 방·참여 행을 영속화한다. */
  private Fixture persistRoom(String nickA, String nickB) {
    Member a = saveMember("a", nickA);
    Member b = saveMember("b", nickB);
    ChatRoom room = chatRoomRepository.save(ChatRoom.createOnMatched(a.getId(), b.getId()));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, a));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, b));
    return new Fixture(room.getId(), a.getId(), b.getId());
  }

  private Member saveMember(String tag, String nickName) {
    String unique = tag + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null);
    // 닉네임은 온보딩 단계에서 채워지지만, 테스트에선 상대 닉네임 노출 검증을 위해 직접 세팅한다.
    ReflectionTestUtils.setField(member, "nickName", nickName);
    return memberRepository.save(member);
  }

  private record Fixture(Long roomId, Long aId, Long bId) {
  }
}
