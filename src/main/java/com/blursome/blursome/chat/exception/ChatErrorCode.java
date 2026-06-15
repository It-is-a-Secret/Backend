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
  PROGRESS_ALREADY_AGREED(HttpStatus.CONFLICT, "이미 동의한 단계입니다.",
      "CHAT_409_PROGRESS_ALREADY_AGREED"),
  ROOM_CREATION_CONFLICT(HttpStatus.CONFLICT,
      "채팅방 개설이 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.",
      "CHAT_409_ROOM_CREATION_CONFLICT");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
