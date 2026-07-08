package com.blursome.blursome.member.domain;

public enum Gender {
  MALE,
  FEMALE;

  /** 반대 성별을 반환한다. 탐색(Discovery)에서 이성 후보를 거르는 데 쓰인다. */
  public Gender opposite() {
    return this == MALE ? FEMALE : MALE;
  }
}
