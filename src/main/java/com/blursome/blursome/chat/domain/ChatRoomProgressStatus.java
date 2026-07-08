package com.blursome.blursome.chat.domain;

public enum ChatRoomProgressStatus {
  MATCHED(null),
  PHOTO_REVEAL_STEP_1("서로의 첫 번째 사진이 공개되었어요."),
  PHOTO_REVEAL_STEP_2("서로의 두 번째 사진이 공개되었어요."),
  PHOTO_REVEAL_STEP_3("서로의 세 번째 사진이 공개되었어요."),
  PHOTO_REVEAL_STEP_4("서로의 네 번째 사진이 공개되었어요."),
  // 마지막 사진 공개 후 마무리
  COMPLETED("서로의 모든 사진이 공개되었어요.");

  /**
   * 이 단계에 도달했을 때 채팅 타임라인에 남길 SYSTEM 메시지 문구. 단계 상승은 항상 {@code PHOTO_REVEAL_STEP_1}
   * 이상으로만 일어나므로 그 단계들은 항상 문구를 가지며, 시작 단계({@code MATCHED})는 안내할 공개가 없어 null이다.
   * 단계 → 문구 매핑은 단계 의미를 소유한 이 enum에 캡슐화한다(이슈 #85).
   */
  private final String revealSystemMessage;

  ChatRoomProgressStatus(String revealSystemMessage) {
    this.revealSystemMessage = revealSystemMessage;
  }

  /** 이 단계 도달 시 남길 SYSTEM 메시지 문구. 안내할 공개가 없는 {@code MATCHED}는 null. */
  public String revealSystemMessage() {
    return revealSystemMessage;
  }

  /**
   * 이 단계가 {@code other} 단계 이상(같거나 더 진행됨)인지 여부.
   * 선언 순서(ordinal)를 단계 순서로 사용하므로 enum 값 순서를 바꾸거나 중간에 삽입하면 안 된다(append만 허용).
   */
  public boolean isAtLeast(ChatRoomProgressStatus other) {
    return this.ordinal() >= other.ordinal();
  }

  /** 마지막 단계({@code COMPLETED}) 여부. */
  public boolean isLast() {
    return this == COMPLETED;
  }

  /**
   * 이 단계에서 공개되는 원본 사진 장수(0~5). 채팅 단계가 오를수록 {@code displayOrder} 순서대로 원본을
   * 1장씩 공개한다(MATCHED=0, PHOTO_REVEAL_STEP_1~4=1~4, COMPLETED=5). 선언 순서(ordinal)가 곧 단계
   * 순서이자 공개 장수이므로 ordinal을 그대로 사용한다(enum 값 순서 변경·중간 삽입 금지, append만 허용).
   *
   * <p>progressStatus → 공개 장수 변환 규칙은 chat 도메인이 소유하며 이 enum에 캡슐화된다. feed 도메인은
   * 이 값(N)만 전달받아 원본 Presigned GET을 발급한다(설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §3·§4).
   * 실제 공개 장수는 회원 보유 사진 수로 캡되며, 그 책임은 feed 서비스에 있다.
   */
  public int revealedOriginalCount() {
    return this.ordinal();
  }

  /**
   * 다음 단계를 반환한다. 마지막 단계에서 호출하면 예외.
   * 서비스가 ordinal을 직접 다루지 않고 단계 진행 의도를 표현하도록 한다.
   */
  public ChatRoomProgressStatus next() {
    if (isLast()) {
      throw new IllegalStateException("이미 마지막 단계입니다: " + this);
    }
    return values()[this.ordinal() + 1];
  }
}
