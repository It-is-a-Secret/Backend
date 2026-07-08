package com.blursome.blursome.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.chat.service.RoomOpenResult;
import com.blursome.blursome.discovery.dto.response.ChatStartResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Member;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatStartServiceTest {

  private static final Long VIEWER_ID = 100L;
  private static final Long TARGET_ID = 200L;
  private static final Long FEED_ID = 500L;
  private static final Long VIEWER_FEED_ID = 1L;
  private static final Long ROOM_ID = 10L;
  private static final String MESSAGE = "안녕하세요, 대화 시작해요";

  @Mock
  private FeedService feedService;

  @Mock
  private BlockRepository blockRepository;

  @Mock
  private ChatRoomService chatRoomService;

  @InjectMocks
  private ChatStartService chatStartService;

  @Test
  @DisplayName("첫 접촉이면 openRoom이 방+첫 메시지를 한 단위로 만들고 created=true·firstMessage로 응답한다")
  void startChat_firstContact_createsRoomAndSendsMessage() {
    // given
    stubViewerEligible();
    stubTargetEligible(Gender.FEMALE, true);
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(false);
    ChatMessageResponse sent = sampleMessage();
    given(chatRoomService.openRoom(eq(VIEWER_ID), eq(TARGET_ID), any(ChatMessageSendRequest.class)))
        .willReturn(new RoomOpenResult(room(ROOM_ID), true, sent));

    // when
    ChatStartResponse response = chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE);

    // then
    assertThat(response.roomId()).isEqualTo(ROOM_ID);
    assertThat(response.created()).isTrue();
    assertThat(response.firstMessage()).isSameAs(sent);
  }

  @Test
  @DisplayName("이미 ACTIVE 방이 있으면 메시지 없이 created=false로 기존 방을 안내한다")
  void startChat_alreadyActive_returnsExistingWithoutMessage() {
    // given
    stubViewerEligible();
    stubTargetEligible(Gender.FEMALE, true);
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(false);
    given(chatRoomService.openRoom(eq(VIEWER_ID), eq(TARGET_ID), any(ChatMessageSendRequest.class)))
        .willReturn(RoomOpenResult.existing(room(ROOM_ID)));

    // when
    ChatStartResponse response = chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE);

    // then
    assertThat(response.roomId()).isEqualTo(ROOM_ID);
    assertThat(response.created()).isFalse();
    assertThat(response.firstMessage()).isNull();
  }

  @Test
  @DisplayName("대상 피드가 없으면 CHAT_START_TARGET_NOT_FOUND 예외가 발생한다")
  void startChat_targetFeedNotFound_throws() {
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.CHAT_START_TARGET_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("자기 자신의 피드면 CANNOT_OPEN_SELF_ROOM 예외가 발생한다")
  void startChat_selfFeed_throws() {
    Feed myFeed = feedOf(VIEWER_ID, Gender.MALE);
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.of(myFeed));

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.CANNOT_OPEN_SELF_ROOM.getCode());
  }

  @Test
  @DisplayName("내 피드가 없으면(온보딩 미완료) DISCOVERY_ONBOARDING_REQUIRED 예외가 발생한다")
  void startChat_viewerHasNoFeed_throws() {
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.of(feedOf(TARGET_ID, Gender.FEMALE)));
    given(feedService.findFeedByMemberId(VIEWER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED.getCode());
  }

  @Test
  @DisplayName("내 피드가 5장 READY가 아니면 CHAT_START_PROFILE_INCOMPLETE 예외가 발생한다")
  void startChat_viewerFeedNotPublishable_throws() {
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.of(feedOf(TARGET_ID, Gender.FEMALE)));
    Feed viewerFeed = feedOf(VIEWER_ID, Gender.MALE);
    ReflectionTestUtils.setField(viewerFeed, "id", VIEWER_FEED_ID);
    given(feedService.findFeedByMemberId(VIEWER_ID)).willReturn(Optional.of(viewerFeed));
    given(feedService.isFeedPublishable(VIEWER_FEED_ID)).willReturn(false);

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code",
            DiscoveryErrorCode.CHAT_START_PROFILE_INCOMPLETE.getCode());
  }

  @Test
  @DisplayName("차단 관계면 대상 게이트보다 먼저 BLOCKED_PARTICIPANT로 막고 방을 열지 않는다")
  void startChat_blocked_throwsBeforeOpeningRoom() {
    stubViewerEligible();
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.of(feedOf(TARGET_ID, Gender.FEMALE)));
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(true);

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.BLOCKED_PARTICIPANT.getCode());
    verify(chatRoomService, never()).openRoom(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName("동성이면 CHAT_START_NOT_ELIGIBLE 예외가 발생한다")
  void startChat_sameGenderTarget_throwsNotEligible() {
    stubViewerEligible();
    stubTargetEligible(Gender.MALE, true);
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(false);

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.CHAT_START_NOT_ELIGIBLE.getCode());
    verify(chatRoomService, never()).openRoom(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName("대상 피드가 비공개면 CHAT_START_NOT_ELIGIBLE 예외가 발생한다")
  void startChat_targetFeedNotPublishable_throwsNotEligible() {
    stubViewerEligible();
    stubTargetEligible(Gender.FEMALE, false);
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(false);

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", DiscoveryErrorCode.CHAT_START_NOT_ELIGIBLE.getCode());
  }

  @Test
  @DisplayName("관계가 종료(CLOSED)면 openRoom의 RELATIONSHIP_CLOSED가 그대로 전파된다")
  void startChat_closedRelationship_propagates() {
    stubViewerEligible();
    stubTargetEligible(Gender.FEMALE, true);
    given(blockRepository.existsBlockBetween(VIEWER_ID, TARGET_ID)).willReturn(false);
    given(chatRoomService.openRoom(eq(VIEWER_ID), eq(TARGET_ID), any(ChatMessageSendRequest.class)))
        .willThrow(BaseException.from(ChatErrorCode.RELATIONSHIP_CLOSED));

    assertThatThrownBy(() -> chatStartService.startChat(VIEWER_ID, FEED_ID, MESSAGE))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.RELATIONSHIP_CLOSED.getCode());
  }

  // ---------- helpers ----------

  /** viewer 피드 존재 + 공개 가능(MALE) 스텁. */
  private void stubViewerEligible() {
    Feed viewerFeed = feedOf(VIEWER_ID, Gender.MALE);
    ReflectionTestUtils.setField(viewerFeed, "id", VIEWER_FEED_ID);
    lenient().when(feedService.findFeedByMemberId(VIEWER_ID)).thenReturn(Optional.of(viewerFeed));
    lenient().when(feedService.isFeedPublishable(VIEWER_FEED_ID)).thenReturn(true);
  }

  /** 대상 피드(성별/공개여부 지정, 활성 회원) 스텁. */
  private void stubTargetEligible(Gender gender, boolean publishable) {
    given(feedService.findFeedById(FEED_ID)).willReturn(Optional.of(feedOf(TARGET_ID, gender)));
    lenient().when(feedService.isFeedPublishable(FEED_ID)).thenReturn(publishable);
  }

  private Feed feedOf(Long memberId, Gender gender) {
    Member member = Member.createOAuthMember(
        com.blursome.blursome.member.domain.OAuthProvider.KAKAO,
        "kakao-" + memberId, "name", "m" + memberId + "@example.com", null);
    ReflectionTestUtils.setField(member, "id", memberId);
    Feed feed = Feed.createOnOnboarding(member, gender, 2000,
        com.blursome.blursome.member.domain.Department.THEOLOGY,
        com.blursome.blursome.member.domain.Mbti.INTJ);
    ReflectionTestUtils.setField(feed, "id", FEED_ID);
    return feed;
  }

  private ChatRoom room(Long id) {
    ChatRoom room = ChatRoom.createOnMatched(VIEWER_ID, TARGET_ID);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }

  private ChatMessageResponse sampleMessage() {
    return new ChatMessageResponse(1L, ROOM_ID, VIEWER_ID,
        com.blursome.blursome.chat.domain.ChatMessageType.TEXT, MESSAGE, null);
  }
}
