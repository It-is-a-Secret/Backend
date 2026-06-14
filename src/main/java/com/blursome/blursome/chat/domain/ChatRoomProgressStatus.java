package com.blursome.blursome.chat.domain;

public enum ChatRoomProgressStatus {
  MATCHED,
  PHOTO_REVEAL_STEP_1,
  PHOTO_REVEAL_STEP_2,
  PHOTO_REVEAL_STEP_3,
  PHOTO_REVEAL_STEP_4,
  // 마지막 사진 공개 후 마무리
  COMPLETED;

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
