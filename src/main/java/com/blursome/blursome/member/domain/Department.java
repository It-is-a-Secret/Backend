package com.blursome.blursome.member.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 학과. 온보딩에서 입력받아 {@link com.blursome.blursome.feed.domain.Feed}에
 * {@code @Enumerated(EnumType.STRING)}으로 저장한다. 자유 문자열 대신 고정 enum으로 받아
 * 표기 흔들림(컴퓨터공학과/컴공/…)을 없애고, enum 밖의 값은 역직렬화 단계에서 자동 차단한다.
 *
 * <p>각 학과는 소속 {@link College}(계열)를 함께 들고 있어 탐색 점수를 join 없이 계산한다(Phase 2):
 * <ul>
 *   <li>동일 학과 가산(+1.0) → {@code a == b}
 *   <li>동일 계열 가산(+0.5) → {@code a.getCollege() == b.getCollege()}
 * </ul>
 *
 * <p>{@code label}은 화면 표시용 한글명이다. enum 특성상 추가·수정은 코드 변경 + 재배포가 필요하며,
 * 선언 순서에 의미가 없으므로 자유롭게 append 한다. (안양대 학과 기준, 안양·강화 양 캠퍼스 포함)
 */
@Getter
@RequiredArgsConstructor
public enum Department {

  // 신학대학
  THEOLOGY("신학과", College.THEOLOGY),
  CHRISTIAN_EDUCATION("기독교교육과", College.THEOLOGY),

  // 인문대학
  KOREAN_LANGUAGE("국어국문학과", College.HUMANITIES),
  ENGLISH("영미언어문화학과", College.HUMANITIES),
  RUSSIAN("러시아언어문화학과", College.HUMANITIES),
  CHINESE("중국언어문화학과", College.HUMANITIES),
  EARLY_CHILDHOOD_EDUCATION("유아교육과", College.HUMANITIES),

  // 예술대학
  PERFORMING_ARTS("공연예술학과", College.ARTS),
  MUSIC("음악학과", College.ARTS),
  DIGITAL_MEDIA_DESIGN("디지털미디어디자인학과", College.ARTS),
  COSMETIC_INVENTION_DESIGN("화장품발명디자인학과", College.ARTS),
  BEAUTY_MEDICAL_DESIGN("뷰티메디컬디자인학과", College.ARTS),
  GAME_CONTENT("게임콘텐츠학과", College.ARTS),
  PRACTICAL_MUSIC("실용음악과", College.ARTS),

  // 스포츠대학
  SPORTS_SCIENCE("스포츠과학과", College.SPORTS),
  SPORTS_APPLIED_INDUSTRY("스포츠응용산업학과", College.SPORTS),

  // 사회과학대학
  GLOBAL_BUSINESS("글로벌경영학과", College.SOCIAL_SCIENCE),
  PUBLIC_ADMINISTRATION("행정학과", College.SOCIAL_SCIENCE),
  TOURISM_MANAGEMENT("관광경영학과", College.SOCIAL_SCIENCE),
  PUBLIC_GOVERNANCE("공공행정학과", College.SOCIAL_SCIENCE),
  TOURISM("관광학과", College.SOCIAL_SCIENCE),

  // 창의융합대학
  FOOD_NUTRITION("식품영양학과", College.CREATIVE_CONVERGENCE),
  COMPUTER_ENGINEERING("컴퓨터공학과", College.CREATIVE_CONVERGENCE),
  INFORMATION_ELECTRICAL_ELECTRONIC("정보전기전자공학과", College.CREATIVE_CONVERGENCE),
  STATISTICS_DATA_SCIENCE("통계데이터사이언스학과", College.CREATIVE_CONVERGENCE),
  SOFTWARE("소프트웨어학과", College.CREATIVE_CONVERGENCE),
  URBAN_INFORMATION_ENGINEERING("도시정보공학과", College.CREATIVE_CONVERGENCE),
  ENVIRONMENTAL_ENERGY_ENGINEERING("환경에너지공학과", College.CREATIVE_CONVERGENCE),
  AI_CONVERGENCE("AI융합학과", College.CREATIVE_CONVERGENCE),
  SMART_CITY_ENGINEERING("스마트시티공학과", College.CREATIVE_CONVERGENCE),
  MARINE_BIO_ENGINEERING("해양바이오공학과", College.CREATIVE_CONVERGENCE),
  CONVERGENCE_SOFTWARE("융합소프트웨어전공학과", College.CREATIVE_CONVERGENCE);

  private final String label;
  private final College college;
}
