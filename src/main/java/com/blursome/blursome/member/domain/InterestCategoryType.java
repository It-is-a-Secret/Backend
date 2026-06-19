package com.blursome.blursome.member.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩에서 선택하는 관심사 카탈로그.
 *
 * <p>스키마({@code interest_category.name})에는 INT 코멘트가 달려 있으나, 본 프로젝트는
 * enum을 {@code @Enumerated(EnumType.STRING)}으로 저장하는 컨벤션을 따르므로 고정 카탈로그
 * enum으로 모델링한다(설계 결정은 {@code docs/member/MEMBER_ONBOARDING.md} 참조).
 *
 * <p>각 값은 화면 표시용 한글 라벨({@link #getLabel()})과, 행에 비정규화 저장되는 기본 설명
 * ({@link #getDescription()})을 가진다. <b>저장은 enum 이름(STRING) 기준</b>이므로 값 이름은
 * 변경하지 말고, 새 카탈로그는 append 만 허용한다.
 */
@Getter
@RequiredArgsConstructor
public enum InterestCategoryType {

  BASKETBALL("농구", "농구는 팀워크와 순발력이 중요한 활동적인 구기 스포츠입니다."),
  SOCCER("축구", "축구는 협동과 체력이 요구되는 대표적인 구기 스포츠입니다."),
  FITNESS("헬스", "헬스는 꾸준함으로 몸과 마음을 단련하는 운동입니다."),
  RUNNING("러닝", "러닝은 언제 어디서나 즐길 수 있는 유산소 운동입니다."),
  READING("독서", "독서는 다양한 지식과 사유를 넓혀 주는 정적인 취미입니다."),
  MOVIE("영화", "영화는 이야기와 영상미를 함께 즐기는 대중적인 취미입니다."),
  MUSIC("음악", "음악은 감상과 연주로 일상에 활력을 더하는 취미입니다."),
  TRAVEL("여행", "여행은 새로운 장소와 경험으로 시야를 넓히는 활동입니다."),
  COOKING("요리", "요리는 재료와 정성으로 결과물을 만드는 창작 활동입니다."),
  GAMING("게임", "게임은 전략과 몰입을 즐기는 인터랙티브한 취미입니다."),
  PHOTOGRAPHY("사진", "사진은 순간을 기록하고 표현하는 시각 예술입니다."),
  CODING("코딩", "코딩은 문제 해결과 창작을 동시에 즐기는 활동입니다."),
  COFFEE("커피", "커피는 한 잔의 여유와 다양한 맛을 탐구하는 취미입니다."),
  PET("반려동물", "반려동물은 교감과 책임감을 함께 나누는 일상입니다.");

  /** 화면 표시용 한글 라벨. */
  private final String label;

  /** 행에 비정규화 저장되는 기본 설명(스키마 {@code description} 예시값에 대응). */
  private final String description;
}
