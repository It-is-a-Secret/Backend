package com.blursome.blursome.chat.repository;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

  /**
   * 내가 아직 나가지 않은({@code leftAt IS NULL}) 방 목록을 방 정보까지 함께 조회한다.
   * 1:1 방은 상대가 먼저 나가면 종료({@code CLOSED})되지만, 남은 참여자는 상대가 나간 것을 확인하고 직접 나갈 때까지
   * 목록에 그대로 남겨 둬야 한다(설계 §7-6). 따라서 방 상태({@code ACTIVE}/{@code CLOSED})로 거르지 않고 내가
   * 나갔는지({@code leftAt})로만 판별한다 — 내가 나간 방만 목록에서 즉시 사라진다.
   * 미리보기 기준(마지막 메시지 id) 내림차순 — 메시지가 없는 새 방은 뒤로 간다(MySQL NULL 정렬).
   */
  @Query("select crm from ChatRoomMember crm "
      + "join fetch crm.chatRoom r "
      + "where crm.member.id = :memberId and crm.leftAt is null "
      + "and not exists (select 1 from ChatRoomMember other, Block b "
      + "where other.chatRoom = r and other.member.id <> :memberId "
      + "and b.blocker.id = :memberId and b.blocked.id = other.member.id) "
      + "order by r.lastMessageId desc")
  List<ChatRoomMember> findActiveMembershipsWithRoom(@Param("memberId") Long memberId);

  /**
   * 조회자({@code memberId})가 같은 방의 상대를 차단했는지 조회한다(이슈 #77, 단방향 비노출). 차단은
   * <b>차단자에게만</b> 비노출이므로, 차단자 방향({@code blocker = 조회자})만 본다 — 피차단자는 방을 계속 보되
   * 송신만 막힌다. {@link #findActiveMembershipsWithRoom}의 목록 필터와 동일 기준을 단건 가시성에 적용한다.
   */
  @Query("select count(b) > 0 from ChatRoomMember other, Block b "
      + "where other.chatRoom.id = :roomId and other.member.id <> :memberId "
      + "and b.blocker.id = :memberId and b.blocked.id = other.member.id")
  boolean existsViewerBlockInRoom(@Param("roomId") Long roomId,
      @Param("memberId") Long memberId);

  /**
   * 두 회원이 모두 참여 중인 동결 가능/해제 가능 방(=ACTIVE 또는 BLOCKED)을 조회한다(이슈 #77). 차단 동결
   * ({@code markBlocked})·해제 복구({@code unblockToActive})가 같은 방을 찾아 상태를 전이하는 데 쓴다. 종료
   * ({@code CLOSED})는 페어 키가 비워져 새 방 대상이고 비가역이므로 제외한다.
   */
  @Query("select r from ChatRoom r "
      + "join ChatRoomMember crm on crm.chatRoom = r "
      + "where r.roomStatus in (com.blursome.blursome.chat.domain.ChatRoomStatus.ACTIVE, "
      + "com.blursome.blursome.chat.domain.ChatRoomStatus.BLOCKED) "
      + "and crm.member.id in (:memberAId, :memberBId) "
      + "group by r "
      + "having count(distinct crm.member.id) = 2")
  Optional<ChatRoom> findActiveOrBlockedRoomBetween(@Param("memberAId") Long memberAId,
      @Param("memberBId") Long memberBId);

  /**
   * 방-회원 참여 행을 방 상태·{@code leftAt}와 무관하게 조회한다(권한 판별용).
   * 행이 없으면 "남의 방"이므로 호출 측에서 403으로 처리하고, 방의 종료(CLOSED) 여부(→404)는 별도로 판단한다.
   */
  @Query("select crm from ChatRoomMember crm "
      + "where crm.chatRoom.id = :roomId and crm.member.id = :memberId")
  Optional<ChatRoomMember> findMembership(@Param("roomId") Long roomId,
      @Param("memberId") Long memberId);

  /**
   * 방의 모든 참여 행을 조회한다(1:1이므로 보통 2행). 사진 공개 단계는 양쪽 멤버의 유효 메시지 누적의
   * 최소값으로 판정하므로(이슈 #79), 본인 멤버십과 상대 멤버십을 한 번에 가져오는 데 쓴다(설계 §7-5).
   */
  @Query("select crm from ChatRoomMember crm where crm.chatRoom.id = :roomId")
  List<ChatRoomMember> findAllByRoomId(@Param("roomId") Long roomId);

  /**
   * 내 읽음 커서({@code lastReadMessageId})를 더 큰 값일 때만 원자적으로 전진시킨다(설계 §7-4, 커서 역행 방지).
   * 엔티티 읽기-수정-쓰기로 갱신하면 동시 읽음 요청이 같은 커서를 읽고 각각 덮어써 더 작은 id가 큰 id를 되돌릴 수 있다
   * (안읽음 수 재증가·잘못된 READ 이벤트). 메시지 id는 단조 증가(IDENTITY)하므로
   * {@code WHERE last_read_message_id IS NULL OR last_read_message_id < :messageId} 조건부 UPDATE 한 방으로
   * DB가 행을 직렬화해 항상 최신 커서만 남도록 한다({@code ChatRoomRepository#advanceLastMessage}와 동일 전략).
   *
   * @return 갱신된 행 수(이미 같거나 더 큰 커서면 0)
   */
  @Modifying
  @Query("update ChatRoomMember crm set crm.lastReadMessageId = :messageId "
      + "where crm.chatRoom.id = :roomId and crm.member.id = :memberId "
      + "and (crm.lastReadMessageId is null or crm.lastReadMessageId < :messageId)")
  int advanceLastReadMessage(@Param("roomId") Long roomId, @Param("memberId") Long memberId,
      @Param("messageId") Long messageId);

  /**
   * 내 현재 읽음 커서를 조회한다(조건부 전진 후 실제 커서 확인용 — 이미 더 큰 커서가 있었으면 그 값을 브로드캐스트해야 한다).
   * 참여 행이 없으면 비어 있음, 행은 있으나 아직 아무것도 안 읽었으면 값이 null이다.
   */
  @Query("select crm.lastReadMessageId from ChatRoomMember crm "
      + "where crm.chatRoom.id = :roomId and crm.member.id = :memberId")
  Optional<Long> findLastReadMessageId(@Param("roomId") Long roomId,
      @Param("memberId") Long memberId);

  /**
   * 방에서 나를 제외한 다른 참여자들의 회원 id를 조회한다(이슈 #88, 새 메시지 개인 알림 팬아웃 대상). 1:1 방이라
   * 보통 상대 1명이며, 발신자 본인은 제외하므로 자기 메시지로 자기 개인 큐가 울리지 않는다. {@code member_id}
   * 컬럼만 투영해 가볍게 조회한다({@code chat_room_id} 선두 유니크 인덱스를 탄다).
   */
  @Query("select crm.member.id from ChatRoomMember crm "
      + "where crm.chatRoom.id = :roomId and crm.member.id <> :memberId")
  List<Long> findOtherMemberIds(@Param("roomId") Long roomId, @Param("memberId") Long memberId);

  /**
   * 주어진 방들에서 나를 제외한 상대 참여자 정보(닉네임 + 읽음 커서)를 한 번에 조회한다(목록 조회 N+1 회피).
   * 1:1 방이므로 방당 한 행이며, 읽음 커서로 "내가 보낸 메시지를 상대가 어디까지 읽었는지"(읽음 표시)를 계산한다.
   * 상대가 아직 아무것도 읽지 않았으면 {@code lastReadMessageId}가, 온보딩 전이면 {@code partnerNickname}이 null일 수 있다.
   */
  @Query("select crm.chatRoom.id as roomId, crm.member.nickName as partnerNickname, "
      + "crm.lastReadMessageId as lastReadMessageId "
      + "from ChatRoomMember crm "
      + "where crm.chatRoom.id in :roomIds and crm.member.id <> :memberId")
  List<RoomPartnerInfo> findPartnerInfos(@Param("roomIds") Collection<Long> roomIds,
      @Param("memberId") Long memberId);
}
