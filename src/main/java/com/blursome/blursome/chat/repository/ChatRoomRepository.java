package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  /**
   * 미리보기용 {@code lastMessageId}를 더 큰 값일 때만 원자적으로 전진시킨다(설계 §8).
   * 동시 송신 시 각 트랜잭션이 같은 방을 읽고 갱신하면 더 작은 id가 큰 id를 덮어써 미리보기가 과거로 되돌아갈 수 있다.
   * 메시지 id는 단조 증가(IDENTITY)하므로, {@code WHERE last_message_id < :messageId} 조건부 UPDATE 한 방으로
   * DB가 직렬화해 항상 최신 id가 남도록 한다(읽기-수정 경합 제거).
   *
   * @return 갱신된 행 수(이미 더 큰 값이 있으면 0)
   */
  @Modifying
  @Query("update ChatRoom r set r.lastMessageId = :messageId "
      + "where r.id = :roomId and (r.lastMessageId is null or r.lastMessageId < :messageId)")
  int advanceLastMessage(@Param("roomId") Long roomId, @Param("messageId") Long messageId);
}
