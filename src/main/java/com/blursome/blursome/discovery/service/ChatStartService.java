package com.blursome.blursome.discovery.service;

import com.blursome.blursome.block.repository.BlockRepository;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.chat.service.RoomOpenResult;
import com.blursome.blursome.discovery.dto.response.ChatStartResponse;
import com.blursome.blursome.discovery.exception.DiscoveryErrorCode;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.service.FeedService;
import com.blursome.blursome.global.exception.BaseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피드로 1:1 대화를 시작하는 유스케이스(이슈 #87, {@code POST /api/discovery/feeds/{feedId}/chat}).
 *
 * <p>탐색에서 발견한 상대의 {@code feedId}와 첫 메시지를 받아 <b>방 생성과 첫 메시지 전송을 한 트랜잭션</b>으로
 * 처리한다. feedId→회원 해석과 디스커버리 게이트 재검증을 이 계층이 담당해(chat 도메인은 회원 id만 안다) feedId
 * 직접 입력이 탐색 후보 필터(이성·피드 공개 5장 READY·차단·활성)를 우회하지 못하게 막는다.
 *
 * <p>검사 순서: 대상 피드 존재 → 자기 자신 차단 → 내 피드 게이트 → 차단 관계(Block 테이블 우선) → 대상 게이트
 * → 관계 상태 분기({@link ChatRoomService#openRoom}). 관계 상태별 결과(첫 접촉/이미 채팅 중/CLOSED/REPORTED/차단)는
 * {@code openRoom}이 정한다.
 */
@Service
@RequiredArgsConstructor
public class ChatStartService {

  private final FeedService feedService;
  private final BlockRepository blockRepository;
  private final ChatRoomService chatRoomService;

  /**
   * 대상 피드에게 첫 메시지를 보내 대화를 시작한다. 첫 접촉이면 방 생성과 첫 메시지 전송이 한 트랜잭션으로 처리되고
   * (둘 중 하나라도 실패하면 둘 다 롤백), 이미 채팅 중(ACTIVE)이면 메시지 없이 기존 방으로 안내한다(둘 다 200).
   * CLOSED/REPORTED/차단/게이트 위반은 도메인 예외로 막는다. 방·첫 메시지의 원자적 저장은 {@link ChatRoomService#openRoom}이
   * 책임진다 — 이 유스케이스는 feedId 해석·게이트 재검증만 담당한다.
   */
  @Transactional
  public ChatStartResponse startChat(Long viewerId, Long feedId, String message) {
    Feed targetFeed = feedService.findFeedById(feedId)
        .orElseThrow(() -> BaseException.from(DiscoveryErrorCode.CHAT_START_TARGET_NOT_FOUND));
    Long targetMemberId = targetFeed.getMember().getId();
    if (viewerId.equals(targetMemberId)) {
      throw BaseException.from(ChatErrorCode.CANNOT_OPEN_SELF_ROOM);
    }

    Feed viewerFeed = requirePublishableViewerFeed(viewerId);
    // Block 테이블 우선 검사(이슈 #77·#87) — 첫 접촉이면 방 자체가 없어 방 상태로는 차단을 판정할 수 없다.
    if (blockRepository.existsBlockBetween(viewerId, targetMemberId)) {
      throw BaseException.from(ChatErrorCode.BLOCKED_PARTICIPANT);
    }
    validateEligibleTarget(viewerFeed, targetFeed, feedId);

    RoomOpenResult result = chatRoomService.openRoom(viewerId, targetMemberId,
        new ChatMessageSendRequest(ChatMessageType.TEXT, message));
    return new ChatStartResponse(result.room().getId(), result.created(), result.firstMessage());
  }

  /**
   * 개설자(나) 게이트: 온보딩으로 피드가 있고 사진 5장이 모두 READY여야 대화를 시작할 수 있다(#72 게이트 재사용).
   * 양쪽이 사진을 교대로 공개하는 모델이라 시작 측도 완성된 피드를 갖춰야 한다.
   */
  private Feed requirePublishableViewerFeed(Long viewerId) {
    Feed viewerFeed = feedService.findFeedByMemberId(viewerId)
        .orElseThrow(() -> BaseException.from(DiscoveryErrorCode.DISCOVERY_ONBOARDING_REQUIRED));
    if (!feedService.isFeedPublishable(viewerFeed.getId())) {
      throw BaseException.from(DiscoveryErrorCode.CHAT_START_PROFILE_INCOMPLETE);
    }
    return viewerFeed;
  }

  /**
   * 대상 게이트: 이성이고, 대상 회원이 활성이며, 대상 피드가 공개 가능(5장 READY)해야 한다. 사유는 노출하지 않고
   * 중립적으로 {@link DiscoveryErrorCode#CHAT_START_NOT_ELIGIBLE}로 가린다(탐색 후보 필터와 동일 기준).
   */
  private void validateEligibleTarget(Feed viewerFeed, Feed targetFeed, Long feedId) {
    boolean eligible = targetFeed.getGender() == viewerFeed.getGender().opposite()
        && targetFeed.getMember().isActive()
        && feedService.isFeedPublishable(feedId);
    if (!eligible) {
      throw BaseException.from(DiscoveryErrorCode.CHAT_START_NOT_ELIGIBLE);
    }
  }
}
