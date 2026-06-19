package com.blursome.blursome.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.event.ChatProgressAdvancedEvent;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

  private static final Long ROOM_ID = 10L;
  private static final Long MEMBER_ID = 100L;
  private static final Long OTHER_ID = 200L;
  private static final Long MY_ROW_ID = 1L;
  private static final Long OTHER_ROW_ID = 2L;

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Mock
  private ChatMessageService chatMessageService;

  @Mock
  private ChatRoomCreator chatRoomCreator;

  @Mock
  private ApplicationEventPublisher eventPublisher;

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

  // ---------- getMyRooms ----------

  @Test
  @DisplayName("내 방 목록을 안읽음 수와 함께 반환한다")
  void getMyRooms_thenReturnsSummariesWithUnread() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    given(chatRoomMemberRepository.findActiveMembershipsWithRoom(MEMBER_ID))
        .willReturn(List.of(membership));
    given(chatMessageService.getUnreadCounts(MEMBER_ID)).willReturn(Map.of(ROOM_ID, 3L));

    // when
    List<ChatRoomSummaryResponse> result = chatRoomService.getMyRooms(MEMBER_ID);

    // then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).roomId()).isEqualTo(ROOM_ID);
    assertThat(result.get(0).unreadCount()).isEqualTo(3L);
  }

  // ---------- getRoom ----------

  @Test
  @DisplayName("참여 중인 방 단건을 안읽음 수와 함께 반환한다")
  void getRoom_whenParticipant_thenReturnsSummary() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));
    given(chatMessageService.getUnreadCount(anyLong(), any(), anyLong())).willReturn(5L);

    // when
    ChatRoomSummaryResponse result = chatRoomService.getRoom(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result.roomId()).isEqualTo(ROOM_ID);
    assertThat(result.unreadCount()).isEqualTo(5L);
  }

  @Test
  @DisplayName("방이 존재하지 않으면 ROOM_NOT_FOUND 예외가 발생한다")
  void getRoom_whenRoomNotFound_thenThrows() {
    // given
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("참여자가 아니면 NOT_PARTICIPANT 예외가 발생한다")
  void getRoom_whenNotParticipant_thenThrows() {
    // given
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(activeRoom(ROOM_ID)));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.NOT_PARTICIPANT.getCode());
  }

  @Test
  @DisplayName("멤버지만 방이 종료(CLOSED)됐으면 ROOM_NOT_FOUND 예외가 발생한다")
  void getRoom_whenRoomClosed_thenThrowsNotFound() {
    // given
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember membership = membership(MY_ROW_ID, closed, ChatRoomProgressStatus.MATCHED);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(closed));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));

    // when & then
    assertThatThrownBy(() -> chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("이미 나간 참여자(leftAt 존재)는 방이 ACTIVE여도 ROOM_NOT_FOUND로 막힌다")
  void getRoom_whenMemberHasLeftButRoomActive_thenThrowsNotFound() {
    // given — 데이터 불일치 가정: 내 참여 행은 나갔지만 방은 아직 ACTIVE
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember left = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    left.leave();
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.of(left));

    // when & then
    assertThatThrownBy(() -> chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  // ---------- getMessageHistory ----------

  @Test
  @DisplayName("참여자면 메시지 이력 조회를 메시지 서비스에 위임한다")
  void getMessageHistory_whenParticipant_thenDelegates() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));
    List<ChatMessageResponse> history = List.of();
    given(chatMessageService.getHistory(anyLong(), any(), anyInt())).willReturn(history);

    // when
    List<ChatMessageResponse> result =
        chatRoomService.getMessageHistory(ROOM_ID, MEMBER_ID, 50L, 30);

    // then
    assertThat(result).isSameAs(history);
    verify(chatMessageService).getHistory(ROOM_ID, 50L, 30);
  }

  // ---------- agreeProgress ----------

  @Test
  @DisplayName("한쪽만 동의하면 방 단계는 그대로지만 내 동의 단계는 오른다")
  void agreeProgress_whenOnlyMeAgreed_thenRoomStays() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember me = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    ChatRoomMember other = membership(OTHER_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    givenVisibleMembership(room, me);
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID)).willReturn(List.of(me, other));
    given(chatMessageService.getUnreadCount(anyLong(), any(), anyLong())).willReturn(0L);

    // when
    ChatRoomSummaryResponse result = chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result.progressStatus()).isEqualTo(ChatRoomProgressStatus.MATCHED);
    assertThat(me.getAgreedProgressStatus()).isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    verify(eventPublisher, never()).publishEvent(any(ChatProgressAdvancedEvent.class));
  }

  @Test
  @DisplayName("양쪽이 모두 동의하면 방 단계가 한 칸 오른다")
  void agreeProgress_whenBothAgreed_thenRoomAdvances() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember me = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    ChatRoomMember other =
        membership(OTHER_ROW_ID, room, ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    givenVisibleMembership(room, me);
    given(chatRoomMemberRepository.findAllByRoomId(ROOM_ID)).willReturn(List.of(me, other));
    given(chatMessageService.getUnreadCount(anyLong(), any(), anyLong())).willReturn(0L);

    // when
    ChatRoomSummaryResponse result = chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result.progressStatus()).isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    ArgumentCaptor<ChatProgressAdvancedEvent> captor =
        ArgumentCaptor.forClass(ChatProgressAdvancedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().roomId()).isEqualTo(ROOM_ID);
    assertThat(captor.getValue().progressStatus())
        .isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
  }

  @Test
  @DisplayName("이미 동의한 단계에 다시 동의하면 PROGRESS_ALREADY_AGREED 예외가 발생한다")
  void agreeProgress_whenAlreadyAgreed_thenThrows() {
    // given — 방은 MATCHED인데 나는 이미 STEP_1에 동의(상대 대기 중)
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember me = membership(MY_ROW_ID, room, ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    givenVisibleMembership(room, me);

    // when & then
    assertThatThrownBy(() -> chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.PROGRESS_ALREADY_AGREED.getCode());
  }

  @Test
  @DisplayName("이미 마지막 단계면 PROGRESS_ALREADY_AGREED 예외가 발생한다")
  void agreeProgress_whenRoomCompleted_thenThrows() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ReflectionTestUtils.setField(room, "progressStatus", ChatRoomProgressStatus.COMPLETED);
    ChatRoomMember me = membership(MY_ROW_ID, room, ChatRoomProgressStatus.COMPLETED);
    givenVisibleMembership(room, me);

    // when & then
    assertThatThrownBy(() -> chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.PROGRESS_ALREADY_AGREED.getCode());
  }

  // ---------- leaveRoom ----------

  @Test
  @DisplayName("나가면 내 참여가 종료되고 1:1 방이 닫힌다")
  void leaveRoom_whenParticipant_thenLeavesAndClosesRoom() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember me = membership(MY_ROW_ID, room, ChatRoomProgressStatus.MATCHED);
    givenVisibleMembership(room, me);

    // when
    chatRoomService.leaveRoom(ROOM_ID, MEMBER_ID);

    // then
    assertThat(me.hasLeft()).isTrue();
    assertThat(room.isActive()).isFalse();
  }

  @Test
  @DisplayName("이미 종료된 방을 나가려 하면 ROOM_NOT_FOUND 예외가 발생한다")
  void leaveRoom_whenRoomClosed_thenThrowsNotFound() {
    // given
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember me = membership(MY_ROW_ID, closed, ChatRoomProgressStatus.MATCHED);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(closed));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.of(me));

    // when & then
    assertThatThrownBy(() -> chatRoomService.leaveRoom(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  // ---------- fixtures ----------

  /** 가시성 검증(getVisibleMembership) 통과를 위한 공통 스텁: ACTIVE 방 + 내 멤버십. */
  private void givenVisibleMembership(ChatRoom room, ChatRoomMember me) {
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.of(me));
  }

  private ChatRoom activeRoom(Long id) {
    ChatRoom room = ChatRoom.createOnMatched(MEMBER_ID, OTHER_ID);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }

  private ChatRoomMember membership(Long rowId, ChatRoom room, ChatRoomProgressStatus agreed) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-" + rowId, "name", "member" + rowId + "@example.com", null);
    ChatRoomMember crm = ChatRoomMember.join(room, member);
    ReflectionTestUtils.setField(crm, "id", rowId);
    ReflectionTestUtils.setField(crm, "agreedProgressStatus", agreed);
    return crm;
  }
}
