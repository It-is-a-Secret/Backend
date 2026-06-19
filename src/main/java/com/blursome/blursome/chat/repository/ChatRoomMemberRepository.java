package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

  /**
   * 내가 현재 참여 중인 활성({@code ACTIVE}) 방 목록을 방 정보까지 함께 조회한다.
   * 1:1 방은 한쪽이 나가면 종료되지만 상대의 {@code leftAt}은 null로 남으므로, {@code leftAt IS NULL}만으로는
   * 종료된 방이 노출된다. 따라서 방 상태가 {@code ACTIVE}인 것만 조회한다.
   * 미리보기 기준(마지막 메시지 id) 내림차순 — 메시지가 없는 새 방은 뒤로 간다(MySQL NULL 정렬).
   */
  @Query("select crm from ChatRoomMember crm "
      + "join fetch crm.chatRoom r "
      + "where crm.member.id = :memberId and crm.leftAt is null "
      + "and r.roomStatus = com.blursome.blursome.chat.domain.ChatRoomStatus.ACTIVE "
      + "order by r.lastMessageId desc")
  List<ChatRoomMember> findActiveMembershipsWithRoom(@Param("memberId") Long memberId);

  /**
   * 방-회원 참여 행을 방 상태·{@code leftAt}와 무관하게 조회한다(권한 판별용).
   * 행이 없으면 "남의 방"이므로 호출 측에서 403으로 처리하고, 방의 종료(CLOSED) 여부(→404)는 별도로 판단한다.
   */
  @Query("select crm from ChatRoomMember crm "
      + "where crm.chatRoom.id = :roomId and crm.member.id = :memberId")
  Optional<ChatRoomMember> findMembership(@Param("roomId") Long roomId,
      @Param("memberId") Long memberId);

  /**
   * 방의 모든 참여 행을 조회한다(1:1이므로 보통 2행). 단계 동의 시 양쪽 멤버의 동의 단계를 함께 비교해야 하므로,
   * 본인 멤버십과 상대 멤버십을 한 번에 가져오는 데 쓴다(설계 §7-5).
   */
  @Query("select crm from ChatRoomMember crm where crm.chatRoom.id = :roomId")
  List<ChatRoomMember> findAllByRoomId(@Param("roomId") Long roomId);

  /**
   * 두 회원이 모두 참여 중인 {@code ACTIVE} 방을 조회한다(중복 방 생성 방지용, 설계 §7-1).
   * 같은 방에 두 회원이 모두 속해 있고 방이 활성일 때만 매칭된다.
   */
  @Query("select r from ChatRoom r "
      + "join ChatRoomMember crm on crm.chatRoom = r "
      + "where r.roomStatus = com.blursome.blursome.chat.domain.ChatRoomStatus.ACTIVE "
      + "and crm.member.id in (:memberAId, :memberBId) "
      + "group by r "
      + "having count(distinct crm.member.id) = 2")
  Optional<ChatRoom> findActiveRoomBetween(@Param("memberAId") Long memberAId,
      @Param("memberBId") Long memberBId);
}
