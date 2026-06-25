package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatRoom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  /**
   * 방 행을 비관적 쓰기 락({@code SELECT ... FOR UPDATE})으로 조회한다(설계 §7-6 동시성).
   *
   * <p>송신·읽음·단계 상승 같은 <b>쓰기</b>는 방이 {@code ACTIVE}임을 확인한 뒤 진행하고, 퇴장({@code leaveRoom})은
   * 방을 {@code CLOSED}로 바꾼다. 두 트랜잭션이 동시에 돌면, 송신이 ACTIVE를 확인한 직후 상대가 나가 방이 닫혀도
   * 메시지가 저장돼 "종료된 방에 송신"이 새어 나갈 수 있다. 쓰기 경로와 퇴장이 <b>같은 방 행</b>에 이 락을 먼저 잡아
   * 직렬화하면, 퇴장이 먼저 커밋된 뒤의 송신은 잠금 해제 후 CLOSED를 보고 {@code ROOM_CLOSED}로 막힌다.
   * 두 경로 모두 항상 방 행을 가장 먼저 잠그므로 잠금 순서가 일관돼 교착이 없다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ChatRoom r where r.id = :roomId")
  Optional<ChatRoom> findByIdForUpdate(@Param("roomId") Long roomId);

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
