package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.RoomUnreadCount;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메시지 저장/조회/읽음 처리 담당. 1단계에서는 이력 조회와 안읽음 집계만 제공한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;

  private final ChatMessageRepository chatMessageRepository;

  /**
   * 방의 메시지 이력을 id 커서 페이지네이션으로 조회한다(최신순). 참여자 검증은 호출 측(Facade)에서 선행한다.
   * {@code size}는 {@code [1, 100]}으로 보정해 잘못된 값(0/음수 → 예외, 과도하게 큰 값 → 과부하)을 방지한다.
   */
  public List<ChatMessageResponse> getHistory(Long roomId, Long cursor, int size) {
    int pageSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    return chatMessageRepository.findHistory(roomId, cursor, PageRequest.ofSize(pageSize))
        .stream()
        .map(ChatMessageResponse::from)
        .toList();
  }

  /** 단건 방의 안읽음 수를 계산한다(목록은 {@link #getUnreadCounts}로 배치 집계). */
  public long getUnreadCount(Long roomId, Long lastReadMessageId, Long memberId) {
    return chatMessageRepository.countUnreadInRoom(roomId, lastReadMessageId, memberId);
  }

  /** 내가 참여 중인 방들의 안읽음 수를 {@code roomId → count} 맵으로 한 번에 계산한다(N+1 회피). */
  public Map<Long, Long> getUnreadCounts(Long memberId) {
    return chatMessageRepository.countUnreadByRoom(memberId)
        .stream()
        .collect(Collectors.toMap(RoomUnreadCount::getRoomId, RoomUnreadCount::getUnreadCount));
  }
}
