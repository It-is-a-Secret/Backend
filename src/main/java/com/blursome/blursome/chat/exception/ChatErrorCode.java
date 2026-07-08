package com.blursome.blursome.chat.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다.", "CHAT_404_ROOM_NOT_FOUND"),
  NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "해당 채팅방의 참여자가 아닙니다.", "CHAT_403_NOT_PARTICIPANT"),
  ROOM_CLOSED(HttpStatus.CONFLICT, "이미 종료된 채팅방입니다.", "CHAT_409_ROOM_CLOSED"),
  INVALID_MESSAGE(HttpStatus.BAD_REQUEST, "메시지 본문 또는 타입이 올바르지 않습니다.", "CHAT_400_INVALID_MESSAGE"),
  CANNOT_OPEN_SELF_ROOM(HttpStatus.BAD_REQUEST, "자기 자신과는 채팅방을 개설할 수 없습니다.",
      "CHAT_400_CANNOT_OPEN_SELF_ROOM"),
  INVALID_ROOM_PARTICIPANTS(HttpStatus.BAD_REQUEST, "채팅방 참여자 정보가 올바르지 않습니다.",
      "CHAT_400_INVALID_ROOM_PARTICIPANTS"),
  ROOM_CREATION_CONFLICT(HttpStatus.CONFLICT,
      "채팅방 개설이 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.",
      "CHAT_409_ROOM_CREATION_CONFLICT"),
  BLOCKED_PARTICIPANT(HttpStatus.CONFLICT, "차단된 상대입니다.",
      "CHAT_409_BLOCKED_PARTICIPANT"),
  // 이슈 #87: 이미 종료(CLOSED)된 관계로 새 대화를 시작하려는 경우. CLOSED는 A-B 관계의 영구 종료라 재시작 불가.
  RELATIONSHIP_CLOSED(HttpStatus.CONFLICT, "종료된 상대입니다.", "CHAT_409_RELATIONSHIP_CLOSED"),
  // 이슈 #87: 신고 누적으로 검토 중(REPORTED)인 관계로 새 대화를 시작하려는 경우(운영자 검토 대기).
  RELATIONSHIP_UNDER_REVIEW(HttpStatus.CONFLICT, "검토 중인 대상입니다.",
      "CHAT_409_RELATIONSHIP_UNDER_REVIEW");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
