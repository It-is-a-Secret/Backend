package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final ChatMessageService chatMessageService;
  private final ChatRoomCreator chatRoomCreator;

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
    ChatRoomMember membership = getVisibleMembership(roomId, memberId);
    long unreadCount = chatMessageService.getUnreadCount(
        roomId, membership.getLastReadMessageId(), memberId);
    return ChatRoomSummaryResponse.of(membership.getChatRoom(), unreadCount);
  }

  /** 방의 메시지 이력을 조회한다. 가시성·권한 검증 후 메시지 서비스에 위임한다. */
  public List<ChatMessageResponse> getMessageHistory(Long roomId, Long memberId, Long cursor,
      int size) {
    getVisibleMembership(roomId, memberId);
    return chatMessageService.getHistory(roomId, cursor, size);
  }

  /**
   * 방 가시성·권한을 판별하고 참여 행을 반환한다.
   * 방 없음 → 404({@code ROOM_NOT_FOUND}), 방은 있으나 내가 멤버가 아님(남의 방) → 403({@code NOT_PARTICIPANT}),
   * 멤버지만 방이 종료(CLOSED)돼 더는 노출되지 않음 → 404({@code ROOM_NOT_FOUND}).
   * 멤버 판별을 먼저 하므로 비참여자는 방의 종료 여부를 알 수 없다(403 우선).
   */
  private ChatRoomMember getVisibleMembership(Long roomId, Long memberId) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    ChatRoomMember membership = chatRoomMemberRepository.findMembership(roomId, memberId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.NOT_PARTICIPANT));
    if (!room.isActive()) {
      throw BaseException.from(ChatErrorCode.ROOM_NOT_FOUND);
    }
    return membership;
  }
}
