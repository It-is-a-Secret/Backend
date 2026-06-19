package com.blursome.blursome.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatRoomMembershipReaderTest {

  private static final Long ROOM_ID = 10L;
  private static final Long MEMBER_ID = 100L;
  private static final Long OTHER_ID = 200L;
  private static final Long MY_ROW_ID = 1L;

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @InjectMocks
  private ChatRoomMembershipReader membershipReader;

  @Test
  @DisplayName("ACTIVE 방의 참여 중인 멤버면 멤버십을 반환한다")
  void getVisibleMembership_whenParticipant_thenReturnsMembership() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));

    // when
    ChatRoomMember result = membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result).isSameAs(membership);
  }

  @Test
  @DisplayName("방이 존재하지 않으면 ROOM_NOT_FOUND 예외가 발생한다")
  void getVisibleMembership_whenRoomNotFound_thenThrows() {
    // given
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("참여자가 아니면 NOT_PARTICIPANT 예외가 발생한다")
  void getVisibleMembership_whenNotParticipant_thenThrows() {
    // given
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(activeRoom(ROOM_ID)));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.NOT_PARTICIPANT.getCode());
  }

  @Test
  @DisplayName("멤버지만 방이 종료(CLOSED)됐으면 ROOM_NOT_FOUND 예외가 발생한다")
  void getVisibleMembership_whenRoomClosed_thenThrowsNotFound() {
    // given
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember membership = membership(MY_ROW_ID, closed);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(closed));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));

    // when & then
    assertThatThrownBy(() -> membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("이미 나간 참여자(leftAt 존재)는 방이 ACTIVE여도 ROOM_NOT_FOUND로 막힌다")
  void getVisibleMembership_whenMemberHasLeftButRoomActive_thenThrowsNotFound() {
    // given — 데이터 불일치 가정: 내 참여 행은 나갔지만 방은 아직 ACTIVE
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember left = membership(MY_ROW_ID, room);
    left.leave();
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID)).willReturn(Optional.of(left));

    // when & then
    assertThatThrownBy(() -> membershipReader.getVisibleMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("쓰기용: ACTIVE 방의 참여 중인 멤버면 멤버십을 반환한다")
  void getWritableMembership_whenParticipant_thenReturnsMembership() {
    // given
    ChatRoom room = activeRoom(ROOM_ID);
    ChatRoomMember membership = membership(MY_ROW_ID, room);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));

    // when
    ChatRoomMember result = membershipReader.getWritableMembership(ROOM_ID, MEMBER_ID);

    // then
    assertThat(result).isSameAs(membership);
  }

  @Test
  @DisplayName("쓰기용: 멤버지만 방이 종료(CLOSED)됐으면 ROOM_CLOSED 예외가 발생한다(404로 숨기지 않음)")
  void getWritableMembership_whenRoomClosed_thenThrowsRoomClosed() {
    // given
    ChatRoom closed = activeRoom(ROOM_ID);
    closed.close();
    ChatRoomMember membership = membership(MY_ROW_ID, closed);
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(closed));
    given(chatRoomMemberRepository.findMembership(ROOM_ID, MEMBER_ID))
        .willReturn(Optional.of(membership));

    // when & then
    assertThatThrownBy(() -> membershipReader.getWritableMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
  }

  @Test
  @DisplayName("쓰기용: 방 없음/비참여는 가시성과 동일하게 ROOM_NOT_FOUND/NOT_PARTICIPANT로 막힌다")
  void getWritableMembership_whenRoomMissingOrNotMember_thenSameAsVisibility() {
    // given — 방 없음
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> membershipReader.getWritableMembership(ROOM_ID, MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  // ---------- fixtures ----------

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
    ReflectionTestUtils.setField(crm, "agreedProgressStatus", ChatRoomProgressStatus.MATCHED);
    return crm;
  }
}
