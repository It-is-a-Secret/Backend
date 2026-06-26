package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 가시성·참여 권한을 판별해 활성 참여 행을 돌려주는 공용 리더(설계 §9 참여자 한정).
 *
 * <p>REST({@link ChatRoomService})와 STOMP({@link ChatMessageService}의 송신/읽음) 양쪽이 같은 규칙으로 참여자를
 * 검증해야 하므로 단일 출처로 추출했다. {@code ChatRoomService → ChatMessageService} 의존이 이미 있어 반대 방향
 * 호출은 순환이 되므로, 검증 로직을 어느 한 서비스에 두지 않고 별도 컴포넌트로 분리한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomMembershipReader {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;

  /**
   * 조회 가시성 규칙으로 참여 행을 반환한다(REST 조회용).
   * 방 없음 → 404({@code ROOM_NOT_FOUND}), 방은 있으나 내가 멤버가 아님(남의 방) → 403({@code NOT_PARTICIPANT}),
   * 멤버지만 내가 이미 나갔으면(leftAt 존재) 더는 노출되지 않음 → 404({@code ROOM_NOT_FOUND}).
   * 멤버 판별을 먼저 하므로 비참여자는 방의 존재 여부를 알 수 없다(403 우선).
   *
   * <p>방이 종료(CLOSED)됐더라도 내가 직접 나가지 않았으면 계속 노출한다(설계 §7-6). 1:1에서 상대가 먼저 나가면 방은
   * {@code CLOSED}가 되지만 남은 참여자는 목록·단건·이력을 그대로 조회할 수 있어야 하므로, 조회 가시성은 방 상태가 아니라
   * "내가 나갔는지({@code leftAt})"로만 판별한다. 쓰기 차단은 {@link #getWritableMembership}가 따로 담당한다.
   *
   * <p>차단 비노출(이슈 #77)도 여기서 함께 막는다 — 내가 상대를 차단한 방은 <b>차단자인 나에게만</b> 숨기되
   * (단방향 비노출), 차단 사실을 직접 노출하지 않도록 차단 전용 코드가 아닌 일반 {@code ROOM_NOT_FOUND}(404, 내가
   * 나간 방과 동일한 중립 응답)로 가린다. 피차단자에게는 방을 숨기지 않으므로 이 필터를 적용하지 않는다.
   */
  public ChatRoomMember getVisibleMembership(Long roomId, Long memberId) {
    // 조회는 락이 필요 없다 — 방 존재만 확인하고(비참여자에게 존재 노출 방지) 내 leftAt으로만 가시성을 판별한다.
    chatRoomRepository.findById(roomId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    ChatRoomMember membership = findMembershipOrThrow(roomId, memberId);
    if (membership.hasLeft()
        || chatRoomMemberRepository.existsViewerBlockInRoom(roomId, memberId)) {
      throw BaseException.from(ChatErrorCode.ROOM_NOT_FOUND);
    }
    return membership;
  }

  /**
   * 쓰기(실시간 송신·읽음·유효 메시지 누적에 따른 단계 상승) 규칙으로 참여 행을 반환한다(STOMP 송신용).
   * 방 없음/비참여 판별은 가시성과 동일하되, 방이 종료(CLOSED)됐거나 내가 나갔으면 "없음(404)"으로 숨기지 않고 명시적으로
   * {@code ROOM_CLOSED}(409)로 알린다(설계 §9·§10, 종료된 방 송신 정책). 조회와 달리 종료된 방으로의 송신·읽음은
   * 양쪽 참여자 모두에게 막혀야 하므로 방 활성 여부({@code isActive})까지 함께 검증한다.
   *
   * <p>방 행을 비관적 쓰기 락({@code findByIdForUpdate})으로 잡아 동시 퇴장과 직렬화한다(설계 §7-6 동시성). 락을 먼저
   * 잡은 뒤 {@code isActive}를 확인하므로, 퇴장이 먼저 커밋된 방으로의 송신은 잠금 해제 후 CLOSED를 보고 막힌다 —
   * "ACTIVE 확인 직후 상대 퇴장"으로 메시지가 종료된 방에 새는 경쟁 조건을 제거한다. {@code leaveRoom}도 같은 행을 잠근다.
   */
  public ChatRoomMember getWritableMembership(Long roomId, Long memberId) {
    ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    ChatRoomMember membership = findMembershipOrThrow(roomId, memberId);
    if (!room.isActive() || membership.hasLeft()) {
      throw BaseException.from(ChatErrorCode.ROOM_CLOSED);
    }
    return membership;
  }

  /**
   * 방 안에서 내 참여 행을 찾는다(비참여 → {@code NOT_PARTICIPANT} 403). 방 존재 확인은 호출 측이 선행하므로,
   * 비참여자는 방 존재 확인(404/락) 단계 이후에야 403을 받는다(403 우선 규칙은 각 메서드가 방 존재를 먼저 보장).
   */
  private ChatRoomMember findMembershipOrThrow(Long roomId, Long memberId) {
    return chatRoomMemberRepository.findMembership(roomId, memberId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.NOT_PARTICIPANT));
  }
}
