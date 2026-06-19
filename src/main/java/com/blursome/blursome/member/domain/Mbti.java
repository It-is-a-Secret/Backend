package com.blursome.blursome.member.domain;

/**
 * 온보딩에서 입력하는 MBTI 16유형. {@code @Enumerated(EnumType.STRING)}으로 저장한다.
 */
public enum Mbti {
  ISTJ, ISFJ, INFJ, INTJ,
  ISTP, ISFP, INFP, INTP,
  ESTP, ESFP, ENFP, ENTP,
  ESTJ, ESFJ, ENFJ, ENTJ
}
