package com.blursome.blursome.chat.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.exception.code.ErrorCode;
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
   * 멤버지만 방이 종료(CLOSED)됐거나 내가 이미 나갔으면(leftAt 존재) 더는 노출되지 않음 → 404({@code ROOM_NOT_FOUND}).
   * 멤버 판별을 먼저 하므로 비참여자는 방의 종료 여부를 알 수 없다(403 우선). 조회에서는 종료된 방을 "없는 것"으로
   * 숨겨 존재를 노출하지 않는다.
   */
  public ChatRoomMember getVisibleMembership(Long roomId, Long memberId) {
    return resolveActiveMembership(roomId, memberId, ChatErrorCode.ROOM_NOT_FOUND);
  }

  /**
   * 쓰기(실시간 송신·읽음) 규칙으로 참여 행을 반환한다(STOMP용).
   * 가시성 규칙과 같되, 참여자 본인이 종료된 방·나간 방에 쓰려 할 때는 "없음(404)"으로 숨기지 않고 명시적으로
   * {@code ROOM_CLOSED}(409)로 알린다(설계 §9·§10, 종료된 방 송신 정책). 방 없음/비참여 판별은 가시성과 동일하다.
   */
  public ChatRoomMember getWritableMembership(Long roomId, Long memberId) {
    return resolveActiveMembership(roomId, memberId, ChatErrorCode.ROOM_CLOSED);
  }

  /**
   * 방·참여 행을 찾아 활성 여부까지 검증한다. 방 없음({@code ROOM_NOT_FOUND})·비참여({@code NOT_PARTICIPANT})는
   * 호출 맥락과 무관하게 같고, 멤버지만 비활성(방 종료 또는 {@code leftAt} 존재)일 때 던질 코드만 호출 측이 정한다.
   *
   * <p>활성 참여자는 {@code leftAt IS NULL}만 해당한다(설계 §9). 1:1에서는 나가기가 곧 방 종료라 보통 방 상태로
   * 걸러지지만, 데이터 불일치·향후 상태 확장에 대비해 참여 행의 {@code leftAt}도 함께 검증한다.
   */
  private ChatRoomMember resolveActiveMembership(Long roomId, Long memberId, ErrorCode inactiveError) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    ChatRoomMember membership = chatRoomMemberRepository.findMembership(roomId, memberId)
        .orElseThrow(() -> BaseException.from(ChatErrorCode.NOT_PARTICIPANT));
    if (!room.isActive() || membership.hasLeft()) {
      throw BaseException.from(inactiveError);
    }
    return membership;
  }
}
