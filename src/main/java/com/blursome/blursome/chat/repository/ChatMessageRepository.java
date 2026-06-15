package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

  /**
   * 방별 메시지 이력을 id 커서 페이지네이션으로 조회한다(최신순).
   * {@code cursor}가 null이면 가장 최근부터, 값이 있으면 그 id보다 과거 메시지를 가져온다.
   * 발신자는 {@code left join fetch}로 함께 로딩해 메시지별 추가 조회(N+1)를 막는다(SYSTEM은 sender=null).
   */
  @Query("select m from ChatMessage m "
      + "left join fetch m.sender "
      + "where m.chatRoom.id = :roomId and (:cursor is null or m.id < :cursor) "
      + "order by m.id desc")
  List<ChatMessage> findHistory(@Param("roomId") Long roomId,
      @Param("cursor") Long cursor,
      Pageable pageable);

  /**
   * 내가 참여 중인 방들의 안읽음 메시지 수를 한 번의 집계 쿼리로 계산한다(목록 조회 N+1 회피, 설계 §7-4).
   * 안읽음 = 내 {@code lastReadMessageId} 이후의 메시지 중 내가 보낸 것이 아닌 메시지(SYSTEM 포함).
   * 안읽음이 0인 방은 결과에 포함되지 않으므로, 호출 측에서 기본값 0으로 처리한다.
   */
  @Query("select m.chatRoom.id as roomId, count(m.id) as unreadCount "
      + "from ChatMessage m, ChatRoomMember crm "
      + "where crm.member.id = :memberId and crm.leftAt is null "
      + "and crm.chatRoom.roomStatus = com.blursome.blursome.chat.domain.ChatRoomStatus.ACTIVE "
      + "and m.chatRoom = crm.chatRoom "
      + "and (crm.lastReadMessageId is null or m.id > crm.lastReadMessageId) "
      + "and (m.sender is null or m.sender.id <> :memberId) "
      + "group by m.chatRoom.id")
  List<RoomUnreadCount> countUnreadByRoom(@Param("memberId") Long memberId);

  /**
   * 단건 방의 안읽음 메시지 수를 센다(목록의 배치 집계 {@link #countUnreadByRoom}와 동일 기준).
   * 안읽음 = 내 {@code lastReadMessageId} 이후의 메시지 중 내가 보낸 것이 아닌 메시지(SYSTEM 포함).
   * 참여자 검증은 호출 측에서 선행하므로 여기서는 방 상태를 다시 보지 않는다.
   */
  @Query("select count(m.id) from ChatMessage m "
      + "where m.chatRoom.id = :roomId "
      + "and (:lastReadMessageId is null or m.id > :lastReadMessageId) "
      + "and (m.sender is null or m.sender.id <> :memberId)")
  long countUnreadInRoom(@Param("roomId") Long roomId,
      @Param("lastReadMessageId") Long lastReadMessageId,
      @Param("memberId") Long memberId);
}
