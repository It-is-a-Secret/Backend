package com.blursome.blursome.discovery.exception;

import com.blursome.blursome.global.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DiscoveryErrorCode implements ErrorCode {

  DISCOVERY_ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN,
      "온보딩을 완료해야 탐색을 이용할 수 있습니다.",
      "DISCOVERY_403_ONBOARDING_REQUIRED"
  ),
  // 이슈 #87: 피드로 대화 시작 시 대상 피드를 찾을 수 없는 경우.
  CHAT_START_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND,
      "대화를 시작할 대상을 찾을 수 없습니다.",
      "DISCOVERY_404_CHAT_START_TARGET_NOT_FOUND"
  ),
  // 이슈 #87: 내 피드 사진 5장이 모두 공개 준비(READY)되지 않아 대화를 시작할 수 없는 경우(개설자 게이트).
  CHAT_START_PROFILE_INCOMPLETE(HttpStatus.FORBIDDEN,
      "피드 사진 5장을 모두 등록해야 대화를 시작할 수 있습니다.",
      "DISCOVERY_403_CHAT_START_PROFILE_INCOMPLETE"
  ),
  // 이슈 #87: 대상이 대화 시작 조건을 충족하지 못하는 경우(이성 아님·피드 비공개·비활성). 사유는 중립적으로 가린다.
  CHAT_START_NOT_ELIGIBLE(HttpStatus.CONFLICT,
      "대화를 시작할 수 없는 상대입니다.",
      "DISCOVERY_409_CHAT_START_NOT_ELIGIBLE"
  );

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
