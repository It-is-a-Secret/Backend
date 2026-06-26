package com.blursome.blursome.chat.service;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.repository.RoomPartnerInfo;
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.exception.BaseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 Facade — 방 개설/조회와 메시지 이력 조회를 조율한다. 방 개설({@link #openRoom})은 REST로 노출하지 않고 매칭 도메인이 호출하는
 * 진입점이다(설계 §7-1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageService chatMessageService;
  private final ChatRoomCreator chatRoomCreator;
  private final ChatRoomMembershipReader membershipReader;
  private final FeedImageService feedImageService;
  private final BlockRepository blockRepository;

  /**
   * 매칭된 두 회원의 1:1 방을 개설한다. 이미 두 회원 사이에 {@code ACTIVE} 방이 있으면 그 방을 반환하고(중복 방지), 없을 때만 새로 만든다. 과거에
   * 종료({@code CLOSED})된 방만 있으면 새 방을 개설한다. 실제 쓰기는 {@link ChatRoomCreator}의 독립 트랜잭션에서 일어나므로 이 메서드 자체는
   * 읽기 전용으로 둔다.
   */
  public ChatRoom openRoom(Long memberAId, Long memberBId) {
    validateDistinctMembers(memberAId, memberBId);
    // 신규 채팅 시작 차단(이슈 #77). 탐색 후보 질의가 이미 차단/피차단을 양방향 제외하지만, 매칭이 직접 개설을
    // 호출하는 경로를 방어한다 — 어느 방향 차단이든 새 방을 열지 않는다(기존 ACTIVE 방은 동결되어 따로 막힘).
    if (blockRepository.existsBlockBetween(memberAId, memberBId)) {
      throw BaseException.from(ChatErrorCode.BLOCKED_PARTICIPANT);
    }
    return chatRoomMemberRepository.findActiveRoomBetween(memberAId, memberBId)
        .orElseGet(() -> createRoom(memberAId, memberBId));
  }

  /**
   * 1:1 고정 모델의 참여자 사전 검증. 잘못된 호출(null/동일 회원)이 하위 제약 위반으로 500으로 새지 않도록 진입점에서 명시적인 400 도메인 예외로 막는다.
   * null 검증을 먼저 하므로 이후 {@code equals}는 NPE-safe.
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
   * 새 방을 만든다. 동시 개설 경합에서 지면(active_pair_key 유니크 제약 위반) 상대가 방금 커밋한 ACTIVE 방을 새 트랜잭션으로 재조회해 그대로
   * 반환한다(완전 멱등). 위반인데도 방이 안 보이면 일시적 경합으로 보고 409로 변환.
   */
  private ChatRoom createRoom(Long memberAId, Long memberBId) {
    try {
      return chatRoomCreator.create(memberAId, memberBId);
    } catch (DataIntegrityViolationException e) {
      return chatRoomCreator.findActiveRoomInNewTx(memberAId, memberBId)
          .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_CREATION_CONFLICT));
    }
  }

  /**
   * 내가 참여 중인 방 목록을 안읽음 수·마지막 메시지 미리보기·상대 정보(닉네임·읽음 커서)와 함께 조회한다. 안읽음·마지막 메시지·상대
   * 정보는 각각 단일 배치 쿼리로 모아 계산해 방 수만큼의 N+1을 피한다(설계 §7-4).
   */
  public List<ChatRoomSummaryResponse> getMyRooms(Long memberId) {
    List<ChatRoomMember> memberships =
        chatRoomMemberRepository.findActiveMembershipsWithRoom(memberId);
    Map<Long, Long> unreadByRoom = chatMessageService.getUnreadCounts(memberId);
    Map<Long, ChatMessageResponse> lastMessages = chatMessageService.getLastMessages(
        memberships.stream().map(m -> m.getChatRoom().getLastMessageId()).toList());
    Map<Long, RoomPartnerInfo> partnerByRoom = findPartnerInfos(memberships, memberId);
    return memberships.stream()
        .map(membership -> {
          ChatRoom room = membership.getChatRoom();
          ChatMessageResponse lastMessage =
              room.getLastMessageId() == null ? null : lastMessages.get(room.getLastMessageId());
          RoomPartnerInfo partner = partnerByRoom.get(room.getId());
          return ChatRoomSummaryResponse.of(
              room,
              partner == null ? null : partner.getPartnerNickname(),
              lastMessage,
              partner == null ? null : partner.getLastReadMessageId(),
              unreadByRoom.getOrDefault(room.getId(), 0L));
        })
        .toList();
  }

  /**
   * 내가 참여 중인 방들의 상대 정보(닉네임·읽음 커서)를 {@code roomId → RoomPartnerInfo} 맵으로 한 번에 조회한다(N+1 회피).
   */
  private Map<Long, RoomPartnerInfo> findPartnerInfos(List<ChatRoomMember> memberships,
      Long memberId) {
    List<Long> roomIds = memberships.stream().map(m -> m.getChatRoom().getId()).toList();
    if (roomIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, RoomPartnerInfo> result = new HashMap<>();
    for (RoomPartnerInfo partner : chatRoomMemberRepository.findPartnerInfos(roomIds, memberId)) {
      result.put(partner.getRoomId(), partner);
    }
    return result;
  }

  /**
   * 방 단건을 조회한다. 가시성·권한 검증 후 단건 안읽음 수·마지막 메시지 미리보기·상대 정보(닉네임·읽음 커서)를 계산한다.
   */
  public ChatRoomSummaryResponse getRoom(Long roomId, Long memberId) {
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    ChatRoom room = membership.getChatRoom();
    long unreadCount = chatMessageService.getUnreadCount(
        roomId, membership.getLastReadMessageId(), memberId);
    ChatMessageResponse lastMessage = chatMessageService.getLastMessage(room.getLastMessageId());
    ChatRoomMember partner = findOtherMembership(roomId, membership);
    return ChatRoomSummaryResponse.of(room, partner.getMember().getNickName(), lastMessage,
        partner.getLastReadMessageId(), unreadCount);
  }

  /**
   * 방의 메시지 이력을 조회한다. 가시성·권한 검증 후 메시지 서비스에 위임한다.
   */
  public List<ChatMessageResponse> getMessageHistory(Long roomId, Long memberId, Long cursor,
      int size) {
    membershipReader.getVisibleMembership(roomId, memberId);
    return chatMessageService.getHistory(roomId, cursor, size);
  }

  /**
   * 채팅방을 나간다(영구, 설계 §7-6). 1:1이므로 한쪽 나가기는 방 종료({@code CLOSED})로 이어진다. 나가는 본인은 즉시 목록에서
   * 사라지고(자신의 {@code leftAt} 세팅) 이후 이력·송신이 모두 막힌다. 반면 남은 상대는 방이 종료돼도 직접 나갈 때까지는
   * 목록·이력을 계속 볼 수 있고(조회 가시성은 방 상태가 아니라 본인 {@code leftAt}으로만 판별), 송신만 {@code ROOM_CLOSED}로
   * 막힌다. 상대가 나간 종료된 방에서 남은 사람도 이 메서드로 직접 나갈 수 있다({@code close()}는 멱등이라 재호출은 no-op).
   * 클래스 기본값({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   */
  @Transactional
  public void leaveRoom(Long roomId, Long memberId) {
    // 방 행을 "가장 먼저" 비관적 쓰기 락으로 잡는다 — 쓰기 경로(getWritableMembership)와 동일한 잠금 순서로 동시 송신·중복
    // 퇴장과 직렬화한다(§7-6 동시성). 락을 먼저 잡은 뒤 멤버십을 읽으므로, 동시 중복 퇴장에서도 항상 최신 참여 상태를 본다
    // (락 전에 읽으면 stale leftAt을 들고 leave()가 두 번 적용될 수 있다). 퇴장이 먼저 커밋되면 락 해제 후 송신은 CLOSED를
    // 보고 ROOM_CLOSED로 막혀 메시지가 종료된 방에 새지 않는다.
    ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    membership.leave();
    room.close();
  }

  /**
   * 차단 발생 시 두 회원 사이 진행 중 방을 동결한다(이슈 #77). 차단 도메인({@code BlockService})이 차단 등록
   * 직후 호출하는 진입점이다(Service → Service). ACTIVE 방이 있으면 {@code BLOCKED}로 전환해 양쪽 송신·원본
   * 공개·단계 진행을 멈춘다. 동결할 방이 없거나(과거 종료/미개설) 이미 동결된 방은 멱등하게 무시한다. 클래스 기본값
   * ({@code readOnly = true})을 덮어 쓰기 트랜잭션으로 연다.
   */
  @Transactional
  public void freezeRoomOnBlock(Long memberAId, Long memberBId) {
    chatRoomMemberRepository.findActiveOrBlockedRoomBetween(memberAId, memberBId)
        .ifPresent(ChatRoom::markBlocked);
  }

  /**
   * 차단이 모두 해제됐을 때 동결된 방을 복구한다(이슈 #77, BLOCKED → ACTIVE). 차단 도메인이 <b>양방향 차단이
   * 모두 사라진 것을 확인한 뒤에만</b> 호출한다 — 반대 방향 차단이 남아 있으면 호출하지 않으므로 여기서는 잔존
   * 차단을 다시 검사하지 않는다. 동결 방이 없거나 이미 ACTIVE면 멱등하게 무시하고, 종료(CLOSED)된 방은
   * {@code unblockToActive}가 되살리지 않는다(비가역).
   */
  @Transactional
  public void restoreRoomOnUnblock(Long memberAId, Long memberBId) {
    chatRoomMemberRepository.findActiveOrBlockedRoomBetween(memberAId, memberBId)
        .ifPresent(ChatRoom::unblockToActive);
  }

  /**
   * 채팅 단계에 따라 상대의 사진을 조회한다(설계 §3·§4, ④-b). 방의 현재 {@code progressStatus}가 정하는 공개
   * 장수 N만큼 상대 원본이 {@code displayOrder} 순서대로 단기 Presigned GET으로 공개되고, 나머지는 블러본으로
   * 내려온다. 공개 장수 산출은 chat 도메인(enum)이 소유하고, 원본/블러본 key·버킷·URL 발급은 feed 도메인이
   * 전담한다 — chat은 N(장수)만 넘기고 feed 내부 구조를 모른다(chat→feed 단방향, Service→Service).
   *
   * <p>참여자 검증은 {@link ChatRoomMembershipReader#getVisibleMembership}로 처리한다(방 없음/내가 나감 →
   * 404, 비참여자 → 403). 다만 <b>원본 공개는 일반 메시지 이력 조회보다 민감</b>하므로, 메시지 이력과 달리 종료
   * ({@code CLOSED})된 방에서는 더 발급하지 않는다 — 상대가 나가면 남은 참여자도 새 원본 Presigned GET을 받지
   * 못하고 {@code ROOM_CLOSED}(409)로 막힌다("참여자 검증 후 발급" 정책). 쓰기 락은 필요 없어(드문 조회, URL은
   * 5분 만료) 비관적 락 대신 활성 여부만 명시 검증한다. 공개 대상은 1:1 방의 상대 회원이다.
   */
  public RevealedFeedImagesResponse getRevealedImages(Long roomId, Long memberId) {
    ChatRoomMember membership = membershipReader.getVisibleMembership(roomId, memberId);
    ChatRoom room = membership.getChatRoom();
    if (!room.isActive()) {
      throw BaseException.from(ChatErrorCode.ROOM_CLOSED);
    }
    int revealCount = room.getProgressStatus().revealedOriginalCount();
    ChatRoomMember partner = findOtherMembership(roomId, membership);
    return feedImageService.issueRevealedImages(partner.getMember().getId(), revealCount);
  }

  /**
   * 1:1 방에서 본인을 제외한 상대 참여 행을 찾는다. 가시성 검증을 통과한 ACTIVE 방은 양쪽이 모두 참여 중이므로 상대 행이 반드시 존재한다(없으면 데이터 정합성
   * 문제 → {@code ROOM_NOT_FOUND}).
   */
  private ChatRoomMember findOtherMembership(Long roomId, ChatRoomMember me) {
    return chatRoomMemberRepository.findAllByRoomId(roomId).stream()
        .filter(member -> !member.getId().equals(me.getId()))
        .findFirst()
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
  }
}
