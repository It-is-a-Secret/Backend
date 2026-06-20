package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.event.ChatProgressAdvancedEvent;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.global.exception.BaseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 Facade — 방 개설/조회와 메시지 이력 조회를 조율한다.
 * 방 개설({@link #openRoom})은 REST로 노출하지 않고 매칭 도메인이 호출하는 진입점이다(설계 §7-1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final ChatMessageService chatMessageService;
  private final ChatRoomCreator chatRoomCreator;
  private final ChatRoomMembershipReader membershipReader;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 매칭된 두 회원의 1:1 방을 개설한다. 이미 두 회원 사이에 {@code ACTIVE} 방이 있으면 그 방을 반환하고(중복 방지),
   * 없을 때만 새로 만든다. 과거에 종료({@code CLOSED})된 방만 있으면 새 방을 개설한다.
   * 실제 쓰기는 {@link ChatRoomCreator}의 독립 트랜잭션에서 일어나므로 이 메서드 자체는 읽기 전용으로 둔다.
   */
  public ChatRoom openRoom(Long memberAId, Long memberBId) {
    validateDistinctMembers(memberAId, memberBId);
    return chatRoomMemberRepository.findActiveRoomBetween(memberAId, memberBId)
        .orElseGet(() -> createRoom(memberAId, memberBId));
  }

  /**
   * 1:1 고정 모델의 참여자 사전 검증. 잘못된 호출(null/동일 회원)이 하위 제약 위반으로 500으로 새지 않도록
   * 진입점에서 명시적인 400 도메인 예외로 막는다. null 검증을 먼저 하므로 이후 {@code equals}는 NPE-safe.
   */
  private void validateDistinctMembers(Long memberAId, Long memberBId) {
    if (memberAId == null || memberBId == null) {
      throw BaseException.from(ChatErrorCode.INVALID_ROOM_PARTICIPANTS);
    }
    if (memberAId.equals(memberBId)) {
      throw BaseException.from(ChatErrorCode.CANNOT_OPEN_SELF_ROOM);
    }
  }

  /**
   * 새 방을 만든다. 동시 개설 경합에서 지면(active_pair_key 유니크 제약 위반) 상대가 방금 커밋한 ACTIVE 방을
   * 새 트랜잭션으로 재조회해 그대로 반환한다(완전 멱등). 위반인데도 방이 안 보이면 일시적 경합으로 보고 409로 변환.
   */
  private ChatRoom createRoom(Long memberAId, Long memberBId) {
    try {
      return chatRoomCreator.create(memberAId, memberBId);
    } catch (DataIntegrityViolationException e) {
      return chatRoomCreator.findActiveRoomInNewTx(memberAId, memberBId)
          .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_CREATION_CONFLICT));
    }
  }

  /** 내가 참여 중인 방 목록을 안읽음 수와 함께 조회한다(안읽음은 단일 집계 쿼리로 계산). */
  public List<ChatRoomSummaryResponse> getMyRooms(Long memberId) {
    List<ChatRoomMember> memberships =
        chatRoomMemberRepository.findActiveMembershipsWithRoom(memberId);
    Map<Long, Long> unreadByRoom = chatMessageService.getUnreadCounts(memberId);
    return memberships.stream()
        .map(membership -> {
          ChatRoom room = membership.getChatRoom();
          return ChatRoomSummaryResponse.of(room, unreadByRoom.getOrDefault(room.getId(), 0L));
        })
        .toList();
  }

  /** 방 단건을 조회한다. 가시성·권한 검증 후 단건 안읽음 수만 계산한다. */
  public ChatRoomSummaryResponse getRoom(Long roomId, Long memberId) {
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    long unreadCount = chatMessageService.getUnreadCount(
        roomId, membership.getLastReadMessageId(), memberId);
    return ChatRoomSummaryResponse.of(membership.getChatRoom(), unreadCount);
  }

  /** 방의 메시지 이력을 조회한다. 가시성·권한 검증 후 메시지 서비스에 위임한다. */
  public List<ChatMessageResponse> getMessageHistory(Long roomId, Long memberId, Long cursor,
      int size) {
    membershipReader.getVisibleMembership(roomId, memberId);
    return chatMessageService.getHistory(roomId, cursor, size);
  }

  /**
   * 다음 단계 공개에 동의한다(설계 §7-5). 동의 대상은 클라이언트 입력이 아니라 서버가 방의 현재 단계 다음으로 계산한다(§9).
   * 내가 동의 단계를 올린 뒤 양쪽 동의가 모두 다음 단계 이상이면 방 단계가 한 칸 오른다.
   * 이미 마지막 단계이거나 이미 그 단계에 동의했으면 {@code PROGRESS_ALREADY_AGREED}(409).
   * 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   *
   * <p>단계가 실제로 오르면 {@link ChatProgressAdvancedEvent}를 발행한다. 상대 클라이언트에 대한 실시간
   * {@code /topic/rooms/{roomId}} 브로드캐스트는 WebSocket 단계에서 이 이벤트를 구독하는 리스너가 담당한다(설계 §7-5).
   */
  @Transactional
  public ChatRoomSummaryResponse agreeProgress(Long roomId, Long memberId) {
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    ChatRoom room = membership.getChatRoom();
    if (room.getProgressStatus().isLast()) {
      throw BaseException.from(ChatErrorCode.PROGRESS_ALREADY_AGREED);
    }
    ChatRoomProgressStatus target = room.getProgressStatus().next();
    try {
      membership.agreeProgress(target);
    } catch (IllegalArgumentException e) {
      // 동의는 단조 증가만 허용 — 이미 그 단계에 동의한 재요청은 도메인이 거부한다.
      throw BaseException.from(ChatErrorCode.PROGRESS_ALREADY_AGREED);
    }
    boolean advanced =
        room.advanceProgressIfBothAgreed(membership, findOtherMembership(roomId, membership));
    if (advanced) {
      eventPublisher.publishEvent(new ChatProgressAdvancedEvent(roomId, room.getProgressStatus()));
    }
    long unreadCount = chatMessageService.getUnreadCount(
        roomId, membership.getLastReadMessageId(), memberId);
    return ChatRoomSummaryResponse.of(room, unreadCount);
  }

  /**
   * 채팅방을 나간다(영구, 설계 §7-6). 1:1이므로 한쪽 나가기는 방 종료({@code CLOSED})로 이어진다.
   * 종료 후에는 양쪽 모두 가시성 검증에서 막혀({@code ROOM_NOT_FOUND}) 재요청이 자연히 차단된다.
   * 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   */
  @Transactional
  public void leaveRoom(Long roomId, Long memberId) {
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    membership.leave();
    membership.getChatRoom().close();
  }

  /**
   * 1:1 방에서 본인을 제외한 상대 참여 행을 찾는다. 가시성 검증을 통과한 ACTIVE 방은 양쪽이 모두 참여 중이므로
   * 상대 행이 반드시 존재한다(없으면 데이터 정합성 문제 → {@code ROOM_NOT_FOUND}).
   */
  private ChatRoomMember findOtherMembership(Long roomId, ChatRoomMember me) {
    return chatRoomMemberRepository.findAllByRoomId(roomId).stream()
        .filter(member -> !member.getId().equals(me.getId()))
        .findFirst()
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
  }
}
