package com.blursome.blursome.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.repository.RoomPartnerInfo;
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

  private static final Long ROOM_ID = 10L;
  private static final Long MEMBER_ID = 100L;
  private static final Long OTHER_ID = 200L;
  private static final Long MY_ROW_ID = 1L;
  private static final Long OTHER_ROW_ID = 2L;
  private static final Long LAST_MESSAGE_ID = 99L;
  private static final Long PARTNER_READ_ID = 90L;
  private static final String PARTNER_NICKNAME = "상대닉";

  @Mock
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatMessageService chatMessageService;

  @Mock
  private ChatRoomCreator chatRoomCreator;

  @Mock
  private ChatRoomMembershipReader membershipReader;

  @Mock
  private FeedImageService feedImageService;

  @Mock
  private BlockRepository blockRepository;

  @InjectMocks
  private ChatRoomService chatRoomService;

  // ---------- openRoom ----------

  @Test
  @DisplayName("두 회원 사이 ACTIVE 방이 있으면 새로 만들지 않고 그 방을 반환한다")
  void openRoom_whenActiveRoomExists_thenReturnsExisting() {
    // given
    ChatRoom existing = activeRoom(ROOM_ID);
    given(chatRoomMemberRepository.findActiveRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.of(existing));

    // when
    ChatRoom result = chatRoomService.openRoom(MEMBER_ID, OTHER_ID);

    // then
    assertThat(result).isSameAs(existing);
    verify(chatRoomCreator, never()).create(anyLong(), anyLong());
  }

  @Test
  @DisplayName("ACTIVE 방이 없으면 새 방을 개설한다")
  void openRoom_whenNoActiveRoom_thenCreatesNew() {
    // given
    ChatRoom created = activeRoom(ROOM_ID);
    given(chatRoomMemberRepository.findActiveRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.empty());
    given(chatRoomCreator.create(MEMBER_ID, OTHER_ID)).willReturn(created);

    // when
    ChatRoom result = chatRoomService.openRoom(MEMBER_ID, OTHER_ID);

    // then
    assertThat(result).isSameAs(created);
  }

  @Test
  @DisplayName("동일 회원으로 방 개설 시 CANNOT_OPEN_SELF_ROOM 예외가 발생한다")
  void openRoom_whenSameMember_thenThrowsCannotOpenSelf() {
    // when & then
    assertThatThrownBy(() -> chatRoomService.openRoom(MEMBER_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.CANNOT_OPEN_SELF_ROOM.getCode());
  }

  @Test
  @DisplayName("참여자 id가 null이면 INVALID_ROOM_PARTICIPANTS 예외가 발생한다")
  void openRoom_whenNullMember_thenThrowsInvalidParticipants() {
    // when & then
    assertThatThrownBy(() -> chatRoomService.openRoom(null, OTHER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.INVALID_ROOM_PARTICIPANTS.getCode());
  }

  @Test
  @DisplayName("동시 개설 경합에서 지면 재조회로 상대가 만든 방을 반환한다")
  void openRoom_whenConflict_thenReturnsRoomFromRetry() {
    // given
    ChatRoom winner = activeRoom(ROOM_ID);
    given(chatRoomMemberRepository.findActiveRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.empty());
    given(chatRoomCreator.create(MEMBER_ID, OTHER_ID))
        .willThrow(new DataIntegrityViolationException("uk_chat_room_active_pair"));
    given(chatRoomCreator.findActiveRoomInNewTx(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.of(winner));

    // when
    ChatRoom result = chatRoomService.openRoom(MEMBER_ID, OTHER_ID);

    // then
    assertThat(result).isSameAs(winner);
  }

  @Test
  @DisplayName("경합 후에도 방이 보이지 않으면 ROOM_CREATION_CONFLICT 예외가 발생한다")
  void openRoom_whenConflictAndStillMissing_thenThrowsConflict() {
    // given
    given(chatRoomMemberRepository.findActiveRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.empty());
    given(chatRoomCreator.create(MEMBER_ID, OTHER_ID))
        .willThrow(new DataIntegrityViolationException("uk_chat_room_active_pair"));
    given(chatRoomCreator.findActiveRoomInNewTx(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> chatRoomService.openRoom(MEMBER_ID, OTHER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CREATION_CONFLICT.getCode());
  }

  @Test
  @DisplayName("차단 관계면 새 방을 열지 않고 BLOCKED_PARTICIPANT 예외가 발생한다(#77)")
  void openRoom_whenBlocked_thenThrowsBlockedParticipant() {
    // given
    given(blockRepository.existsBlockBetween(MEMBER_ID, OTHER_ID)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> chatRoomService.openRoom(MEMBER_ID, OTHER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.BLOCKED_PARTICIPANT.getCode());
    verify(chatRoomMemberRepository, never()).findActiveRoomBetween(anyLong(), anyLong());
    verify(chatRoomCreator, never()).create(anyLong(), anyLong());
  }

  // ---------- freeze / restore (#77) ----------

  @Test
  @DisplayName("차단 동결: 진행 중 ACTIVE 방을 BLOCKED로 전환한다")
  void freezeRoomOnBlock_whenActiveRoom_thenBlocked() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    given(chatRoomMemberRepository.findActiveOrBlockedRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.of(room));

    // when
    chatRoomService.freezeRoomOnBlock(MEMBER_ID, OTHER_ID);

    // then — CLOSED/REPORTED가 아닌 BLOCKED로 동결됐는지 직접 검증한다
    assertThat(ReflectionTestUtils.getField(room, "roomStatus")).isEqualTo(ChatRoomStatus.BLOCKED);
  }

  @Test
  @DisplayName("차단 동결: 동결할 방이 없으면 멱등하게 무시한다")
  void freezeRoomOnBlock_whenNoRoom_thenNoOp() {
    given(chatRoomMemberRepository.findActiveOrBlockedRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.empty());

    chatRoomService.freezeRoomOnBlock(MEMBER_ID, OTHER_ID);
    // 예외 없이 통과하면 성공(no-op)
  }

  @Test
  @DisplayName("차단 해제 복구: 동결된 BLOCKED 방을 ACTIVE로 되살린다")
  void restoreRoomOnUnblock_whenBlockedRoom_thenActive() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    room.markBlocked();
    given(chatRoomMemberRepository.findActiveOrBlockedRoomBetween(MEMBER_ID, OTHER_ID))
        .willReturn(Optional.of(room));

    // when
    chatRoomService.restoreRoomOnUnblock(MEMBER_ID, OTHER_ID);

    // then
    assertThat(room.isActive()).isTrue();
  }

  // ---------- getMyRooms ----------

  @Test
  @DisplayName("내 방 목록을 안읽음 수·단계·마지막 메시지·상대 닉네임·상대 읽음 커서와 함께 반환한다")
  void getMyRooms_thenReturnsSummariesWithUnread() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ReflectionTestUtils.setField(room, "lastMessageId", LAST_MESSAGE_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room);
    given(chatRoomMemberRepository.findActiveMembershipsWithRoom(MEMBER_ID))
        .willReturn(List.of(membership));
    given(chatMessageService.getUnreadCounts(MEMBER_ID)).willReturn(Map.of(ROOM_ID, 3L));
    ChatMessageResponse lastMessage = lastMessageResponse();
    given(chatMessageService.getLastMessages(List.of(LAST_MESSAGE_ID)))
        .willReturn(Map.of(LAST_MESSAGE_ID, lastMessage));
    given(chatRoomMemberRepository.findPartnerInfos(List.of(ROOM_ID), MEMBER_ID))
        .willReturn(List.of(partnerInfo(ROOM_ID, PARTNER_NICKNAME, PARTNER_READ_ID)));

    // when
    List<ChatRoomSummaryResponse> result = chatRoomService.getMyRooms(MEMBER_ID);

    // then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).roomId()).isEqualTo(ROOM_ID);
    assertThat(result.get(0).progressStatus()).isEqualTo(ChatRoomProgressStatus.MATCHED);
    assertThat(result.get(0).unreadCount()).isEqualTo(3L);
    assertThat(result.get(0).lastMessage()).isSameAs(lastMessage);
    assertThat(result.get(0).partnerNickname()).isEqualTo(PARTNER_NICKNAME);
    assertThat(result.get(0).partnerLastReadMessageId()).isEqualTo(PARTNER_READ_ID);
  }

  // ---------- getRoom ----------

  @Test
  @DisplayName("참여 중인 방 단건을 안읽음 수·상대 닉네임·상대 읽음 커서와 함께 반환한다")
  void getRoom_whenParticipant_thenReturnsSummary() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room);
    ChatRoomMember other = membership(OTHER_ROW_ID, room);
    ReflectionTestUtils.setField(other, "lastReadMessageId", PARTNER_READ_ID);
    ReflectionTestUtils.setField(other.getMember(), "nickName", PARTNER_NICKNAME);
    givenVisibleMembership(room, membership);
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID)).willReturn(List.of(membership, other));
    given(chatMessageService.getUnreadCount(anyLong(), any(), anyLong())).willReturn(5L);

    // when
    ChatRoomSummaryResponse result = chatRoomService.getRoom(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result.roomId()).isEqualTo(ROOM_ID);
    assertThat(result.unreadCount()).isEqualTo(5L);
    assertThat(result.partnerNickname()).isEqualTo(PARTNER_NICKNAME);
    assertThat(result.partnerLastReadMessageId()).isEqualTo(PARTNER_READ_ID);
  }

  @Test
  @DisplayName("가시성 검증에서 거부되면(reader 예외) 그대로 전파한다")
  void getRoom_whenNotVisible_thenPropagates() {
    // given — 방 없음/비참여/종료/퇴장 등 구체적 판별은 ChatRoomMembershipReaderTest가 담당한다.
    given(membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .willThrow(BaseException.from(ChatErrorCode.NOT_PARTICIPANT));

    // when & then
    assertThatThrownBy(() -> chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.NOT_PARTICIPANT.getCode());
  }

  // ---------- getMessageHistory ----------

  @Test
  @DisplayName("참여자면 메시지 이력 조회를 메시지 서비스에 위임한다")
  void getMessageHistory_whenParticipant_thenDelegates() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room);
    givenVisibleMembership(room, membership);
    List<ChatMessageResponse> history = List.of();
    given(chatMessageService.getHistory(anyLong(), any(), anyInt())).willReturn(history);

    // when
    List<ChatMessageResponse> result =
        chatRoomService.getMessageHistory(ROOM_ID, MEMBER_ID, 50L, 30);

    // then
    assertThat(result).isSameAs(history);
    verify(chatMessageService).getHistory(ROOM_ID, 50L, 30);
  }

  // ---------- leaveRoom ----------

  @Test
  @DisplayName("나가면 내 참여가 종료되고 1:1 방이 닫힌다(방 행을 비관적 락으로 잡고 닫음)")
  void leaveRoom_whenParticipant_thenLeavesAndClosesRoom() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember me = membership(MY_ROW_ID, room);
    givenVisibleMembership(room, me);
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room));

    // when
    chatRoomService.leaveRoom(ROOM_ID, MEMBER_ID);

    // then
    assertThat(me.hasLeft()).isTrue();
    assertThat(room.isActive()).isFalse();
    verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
  }

  @Test
  @DisplayName("상대가 먼저 나가 종료(CLOSED)된 방에서도 남은 사람이 나갈 수 있다(close()는 멱등)")
  void leaveRoom_whenRoomAlreadyClosed_thenRemainingMemberCanLeave() {
    // given — 상대가 먼저 나가 방은 CLOSED지만 내 leftAt은 null이라 조회 가시성은 통과
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember me = membership(MY_ROW_ID, closed);
    givenVisibleMembership(closed, me);
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(closed));

    // when
    chatRoomService.leaveRoom(ROOM_ID, MEMBER_ID);

    // then — 내 참여만 종료되고 방은 이미 CLOSED 그대로(멱등 no-op)
    assertThat(me.hasLeft()).isTrue();
    assertThat(closed.isActive()).isFalse();
  }

  // ---------- getRevealedImages ----------

  @Test
  @DisplayName("단계별 사진 조회: 공개 장수만큼 본인(ME)·상대(PARTNER) 사진을 각각 feed 서비스에 위임해 한 목록으로 합친다")
  void getRevealedImages_delegatesBothSidesAndMergesWithRole() {
    // given — 방은 STEP_2(공개 장수 2), 본인 id는 MEMBER_ID, 상대 id는 OTHER_ID
    ChatRoom room = activeRoom(ROOM_ID);
    ReflectionTestUtils.setField(room, "progressStatus", ChatRoomProgressStatus.PHOTO_REVEAL_STEP_2);
    ChatRoomMember me = membership(MY_ROW_ID, room);
    ChatRoomMember other = membership(OTHER_ROW_ID, room);
    ReflectionTestUtils.setField(other.getMember(), "id", OTHER_ID);
    givenVisibleMembership(room, me);
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID)).willReturn(List.of(me, other));
    RevealedFeedImagesResponse.Image mine = new RevealedFeedImagesResponse.Image(
        RevealedFeedImagesResponse.Role.ME, 1, true, "https://original/me/1");
    RevealedFeedImagesResponse.Image theirs = new RevealedFeedImagesResponse.Image(
        RevealedFeedImagesResponse.Role.PARTNER, 1, true, "https://original/partner/1");
    given(feedImageService.issueRevealedImages(MEMBER_ID, 2, RevealedFeedImagesResponse.Role.ME))
        .willReturn(new RevealedFeedImagesResponse(List.of(mine)));
    given(feedImageService.issueRevealedImages(OTHER_ID, 2, RevealedFeedImagesResponse.Role.PARTNER))
        .willReturn(new RevealedFeedImagesResponse(List.of(theirs)));

    // when
    RevealedFeedImagesResponse result = chatRoomService.getRevealedImages(ROOM_ID, MEMBER_ID);

    // then — 본인·상대 각각 공개 장수 2로 위임하고 두 결과를 ME, PARTNER 순서로 합친다.
    assertThat(result.images()).containsExactly(mine, theirs);
    verify(feedImageService).issueRevealedImages(MEMBER_ID, 2, RevealedFeedImagesResponse.Role.ME);
    verify(feedImageService).issueRevealedImages(OTHER_ID, 2, RevealedFeedImagesResponse.Role.PARTNER);
  }

  @Test
  @DisplayName("단계별 사진 조회: 차단/신고/종료로 비활성인 방이면 ROOM_CLOSED로 막고 feed 서비스를 호출하지 않는다")
  void getRevealedImages_whenRoomNotActive_thenThrowsAndDoesNotDelegate() {
    // given — 상대가 먼저 나가 방은 CLOSED지만 내 leftAt은 null이라 조회 가시성은 통과
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember me = membership(MY_ROW_ID, closed);
    givenVisibleMembership(closed, me);

    // when & then — 원본 공개는 ACTIVE 방으로 한정, 비활성은 기존 계약대로 409.
    assertThatThrownBy(() -> chatRoomService.getRevealedImages(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
    verify(feedImageService, never()).issueRevealedImages(anyLong(), anyInt(), any());
  }

  @Test
  @DisplayName("단계별 사진 조회: 가시성 검증(getVisibleMembership)을 통과하지 못하면 feed 서비스를 호출하지 않는다")
  void getRevealedImages_whenNotVisible_thenDoesNotDelegate() {
    given(membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .willThrow(BaseException.from(ChatErrorCode.NOT_PARTICIPANT));

    assertThatThrownBy(() -> chatRoomService.getRevealedImages(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.NOT_PARTICIPANT.getCode());
    verify(feedImageService, never()).issueRevealedImages(anyLong(), anyInt(), any());
  }

  // ---------- fixtures ----------

  /** 가시성 검증을 통과해 내 멤버십을 돌려주도록 reader를 스텁한다(방은 멤버십에서 reachable). */
  private void givenVisibleMembership(ChatRoom room, ChatRoomMember me) {
    given(membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID)).willReturn(me);
  }

  private ChatMessageResponse lastMessageResponse() {
    return new ChatMessageResponse(
        LAST_MESSAGE_ID, ROOM_ID, OTHER_ID, ChatMessageType.TEXT, "마지막 메시지",
        LocalDateTime.now());
  }

  /** 상대 정보(닉네임·읽음 커서) 배치 조회 프로젝션 스텁. */
  private RoomPartnerInfo partnerInfo(Long roomId, String nickname, Long lastReadMessageId) {
    return new RoomPartnerInfo() {
      @Override
      public Long getRoomId() {
        return roomId;
      }

      @Override
      public String getPartnerNickname() {
        return nickname;
      }

      @Override
      public Long getLastReadMessageId() {
        return lastReadMessageId;
      }
    };
  }

  private ChatRoom activeRoom(Long id) {
    ChatRoom room = ChatRoom.createOnMatched(MEMBER_ID, OTHER_ID);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }

  private ChatRoomMember membership(Long rowId, ChatRoom room) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-" + rowId, "name", "member" + rowId + "@example.com", null);
    ChatRoomMember crm = ChatRoomMember.join(room, member);
    ReflectionTestUtils.setField(crm, "id", rowId);
    return crm;
  }
}
