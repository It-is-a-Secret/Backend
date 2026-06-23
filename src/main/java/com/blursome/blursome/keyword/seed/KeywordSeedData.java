package com.blursome.blursome.keyword.seed;

import com.blursome.blursome.keyword.domain.RelationType;
import java.util.List;

/**
 * 키워드 카탈로그·관계 시드 정의(기획 v1.1 §7.3·§7.4).
 *
 * <p>카테고리의 {@code sortOrder}는 {@link #CATEGORIES} 선언 순서를 따른다. 관계는 순서 무관 유일 쌍이며,
 * {@link RelationSeed}의 두 코드 순서는 의미가 없다(저장 시 태그 id 기준으로 정규화).
 *
 * <p>검토 개선안 #2(보완형 중복 행)·#3(보완↔충돌 양립)을 반영해 <b>한 쌍은 한 유형</b>으로만 등재한다.
 * 예: {@code 운동↔산책}은 유사 그룹에 있으므로 SIMILAR로만, {@code 즉흥형↔계획형}은 보완으로만 둔다.
 * 충돌(CONFLICT) 쌍은 기획 §7.4.4의 충돌 기준 정규화가 끝난 뒤 보강한다(현재 미등재).
 */
public final class KeywordSeedData {

  private KeywordSeedData() {
  }

  public record TagSeed(String code, String name) {

  }

  public record CategorySeed(String code, String name, boolean required, List<TagSeed> tags) {

  }

  public record RelationSeed(String codeA, String codeB, RelationType type) {

  }

  public static final List<CategorySeed> CATEGORIES = List.of(
      new CategorySeed("HOBBY", "취미/여가", true, List.of(
          new TagSeed("HOBBY_EXERCISE", "운동"),
          new TagSeed("HOBBY_WALK", "산책"),
          new TagSeed("HOBBY_READING", "독서/글쓰기"),
          new TagSeed("HOBBY_EXHIBITION", "전시/영화"),
          new TagSeed("HOBBY_OTT", "OTT"),
          new TagSeed("HOBBY_GAME", "게임"),
          new TagSeed("HOBBY_TRAVEL", "여행/캠핑"),
          new TagSeed("HOBBY_CONCERT", "공연/콘서트")
      )),
      new CategorySeed("FOOD", "음식", false, List.of(
          new TagSeed("FOOD_DESSERT", "디저트러버"),
          new TagSeed("FOOD_SPICY", "매운맛러버"),
          new TagSeed("FOOD_MEAT", "고기러버"),
          new TagSeed("FOOD_SEAFOOD", "해산물러버"),
          new TagSeed("FOOD_KOREAN", "한식"),
          new TagSeed("FOOD_WESTERN", "양식"),
          new TagSeed("FOOD_JAPANESE", "일식"),
          new TagSeed("FOOD_DRINK", "가벼운 음주")
      )),
      new CategorySeed("LIFESTYLE", "라이프스타일", false, List.of(
          new TagSeed("LIFE_HOMEBODY", "집순이/집돌이"),
          new TagSeed("LIFE_OUTGOING", "밖순이/밖돌이"),
          new TagSeed("LIFE_MORNING", "아침형"),
          new TagSeed("LIFE_NIGHT", "야행형"),
          new TagSeed("LIFE_SPONTANEOUS", "즉흥형"),
          new TagSeed("LIFE_PLANNED", "계획형"),
          new TagSeed("LIFE_MINIMAL", "미니멀"),
          new TagSeed("LIFE_MAXIMAL", "맥시멈"),
          new TagSeed("LIFE_TIDY", "깔끔함")
      )),
      new CategorySeed("PERSONALITY", "성격/대화 스타일", true, List.of(
          new TagSeed("PERS_KIND", "다정함"),
          new TagSeed("PERS_CHEERFUL", "유쾌함"),
          new TagSeed("PERS_CALM", "차분함"),
          new TagSeed("PERS_LIVELY", "활발함"),
          new TagSeed("PERS_HONEST", "솔직함"),
          new TagSeed("PERS_SENSIBLE", "센스있음"),
          new TagSeed("PERS_TALKATIVE", "수다쟁이"),
          new TagSeed("PERS_LISTENER", "경청형"),
          new TagSeed("PERS_PLAYFUL", "장난많음"),
          new TagSeed("PERS_SHY", "낯가림"),
          new TagSeed("PERS_SERIOUS", "진지토크")
      )),
      new CategorySeed("DATE", "데이트 취향", false, List.of(
          new TagSeed("DATE_CAFE", "카페데이트"),
          new TagSeed("DATE_RESTAURANT", "맛집데이트"),
          new TagSeed("DATE_MOVIE", "영화/전시데이트"),
          new TagSeed("DATE_ACTIVITY", "액티비티데이트"),
          new TagSeed("DATE_SPONTANEOUS", "즉흥데이트"),
          new TagSeed("DATE_PLANNED", "계획데이트"),
          new TagSeed("DATE_HOME", "집데이트"),
          new TagSeed("DATE_DRIVE", "드라이브")
      )),
      new CategorySeed("CONTACT", "연락/관계 스타일", true, List.of(
          new TagSeed("CONTACT_OFTEN", "연락자주"),
          new TagSeed("CONTACT_WHEN_NEEDED", "연락필요할때"),
          new TagSeed("CONTACT_FAST_REPLY", "빠른답장"),
          new TagSeed("CONTACT_SLOW_REPLY", "느긋한답장"),
          new TagSeed("CONTACT_SLOW_BOND", "천천히친해짐"),
          new TagSeed("CONTACT_FAST_BOND", "빠르게친해짐"),
          new TagSeed("CONTACT_EXPRESSIVE", "표현많음"),
          new TagSeed("CONTACT_COMFORTABLE", "편안한관계"),
          new TagSeed("CONTACT_ANNIVERSARY", "기념일챙김")
      )),
      new CategorySeed("PET", "반려동물", false, List.of(
          new TagSeed("PET_DOG", "강아지좋아함"),
          new TagSeed("PET_CAT", "고양이좋아함"),
          new TagSeed("PET_HAS", "반려동물있음"),
          new TagSeed("PET_NONE", "반려동물없음"),
          new TagSeed("PET_LOVER", "동물좋아함")
      )),
      new CategorySeed("VALUES", "가치관/관심사", false, List.of(
          new TagSeed("VALUE_SELF_DEV", "자기계발"),
          new TagSeed("VALUE_CAREER", "커리어"),
          new TagSeed("VALUE_FINANCE", "재테크"),
          new TagSeed("VALUE_HEALTH", "건강관리"),
          new TagSeed("VALUE_STABILITY", "안정감"),
          new TagSeed("VALUE_CHALLENGE", "도전적인"),
          new TagSeed("VALUE_RELATIONSHIP", "인간관계중시"),
          new TagSeed("VALUE_FAMILY", "가족중시")
      ))
  );

  /**
   * 태그 간 관계 시드. 한 쌍은 한 유형으로만 등재(검토 #2·#3 정규화 반영).
   * 유사(SIMILAR)는 기획 §7.4.2 그룹을 쌍으로 전개, 보완(COMPLEMENT)은 §7.4.3에서 유사와 중복되지 않는 쌍만 등재.
   */
  public static final List<RelationSeed> RELATIONS = List.of(
      // 유사 — 취미/여가
      new RelationSeed("HOBBY_EXERCISE", "HOBBY_WALK", RelationType.SIMILAR),
      new RelationSeed("HOBBY_READING", "HOBBY_EXHIBITION", RelationType.SIMILAR),
      new RelationSeed("HOBBY_READING", "HOBBY_OTT", RelationType.SIMILAR),
      new RelationSeed("HOBBY_EXHIBITION", "HOBBY_OTT", RelationType.SIMILAR),
      new RelationSeed("HOBBY_EXHIBITION", "HOBBY_CONCERT", RelationType.SIMILAR),
      new RelationSeed("HOBBY_TRAVEL", "HOBBY_WALK", RelationType.SIMILAR),
      new RelationSeed("HOBBY_GAME", "HOBBY_OTT", RelationType.SIMILAR),
      // 유사 — 음식
      new RelationSeed("FOOD_KOREAN", "FOOD_WESTERN", RelationType.SIMILAR),
      new RelationSeed("FOOD_KOREAN", "FOOD_JAPANESE", RelationType.SIMILAR),
      new RelationSeed("FOOD_WESTERN", "FOOD_JAPANESE", RelationType.SIMILAR),
      new RelationSeed("FOOD_MEAT", "FOOD_SEAFOOD", RelationType.SIMILAR),
      // 유사 — 라이프스타일
      new RelationSeed("LIFE_HOMEBODY", "LIFE_MINIMAL", RelationType.SIMILAR),
      new RelationSeed("LIFE_HOMEBODY", "LIFE_TIDY", RelationType.SIMILAR),
      new RelationSeed("LIFE_MINIMAL", "LIFE_TIDY", RelationType.SIMILAR),
      new RelationSeed("LIFE_MORNING", "LIFE_PLANNED", RelationType.SIMILAR),
      new RelationSeed("LIFE_MORNING", "LIFE_TIDY", RelationType.SIMILAR),
      new RelationSeed("LIFE_PLANNED", "LIFE_TIDY", RelationType.SIMILAR),
      new RelationSeed("LIFE_NIGHT", "LIFE_SPONTANEOUS", RelationType.SIMILAR),
      // 유사 — 성격/대화
      new RelationSeed("PERS_KIND", "PERS_LISTENER", RelationType.SIMILAR),
      new RelationSeed("PERS_KIND", "PERS_SERIOUS", RelationType.SIMILAR),
      new RelationSeed("PERS_LISTENER", "PERS_SERIOUS", RelationType.SIMILAR),
      new RelationSeed("PERS_CHEERFUL", "PERS_LIVELY", RelationType.SIMILAR),
      new RelationSeed("PERS_CHEERFUL", "PERS_TALKATIVE", RelationType.SIMILAR),
      new RelationSeed("PERS_CHEERFUL", "PERS_PLAYFUL", RelationType.SIMILAR),
      new RelationSeed("PERS_LIVELY", "PERS_TALKATIVE", RelationType.SIMILAR),
      new RelationSeed("PERS_LIVELY", "PERS_PLAYFUL", RelationType.SIMILAR),
      new RelationSeed("PERS_TALKATIVE", "PERS_PLAYFUL", RelationType.SIMILAR),
      new RelationSeed("PERS_CALM", "PERS_SHY", RelationType.SIMILAR),
      // 유사 — 데이트/연락/가치관
      new RelationSeed("DATE_ACTIVITY", "DATE_SPONTANEOUS", RelationType.SIMILAR),
      new RelationSeed("DATE_ACTIVITY", "DATE_DRIVE", RelationType.SIMILAR),
      new RelationSeed("DATE_SPONTANEOUS", "DATE_DRIVE", RelationType.SIMILAR),
      new RelationSeed("CONTACT_OFTEN", "CONTACT_FAST_REPLY", RelationType.SIMILAR),
      new RelationSeed("CONTACT_OFTEN", "CONTACT_EXPRESSIVE", RelationType.SIMILAR),
      new RelationSeed("CONTACT_FAST_REPLY", "CONTACT_EXPRESSIVE", RelationType.SIMILAR),
      new RelationSeed("VALUE_SELF_DEV", "VALUE_CAREER", RelationType.SIMILAR),
      new RelationSeed("VALUE_SELF_DEV", "VALUE_CHALLENGE", RelationType.SIMILAR),
      new RelationSeed("VALUE_CAREER", "VALUE_CHALLENGE", RelationType.SIMILAR),

      // 보완 — 유사와 중복되지 않는 쌍만(주로 카테고리 교차)
      new RelationSeed("HOBBY_READING", "PERS_SERIOUS", RelationType.COMPLEMENT),
      new RelationSeed("HOBBY_EXHIBITION", "DATE_CAFE", RelationType.COMPLEMENT),
      new RelationSeed("HOBBY_OTT", "DATE_HOME", RelationType.COMPLEMENT),
      new RelationSeed("HOBBY_GAME", "DATE_HOME", RelationType.COMPLEMENT),
      new RelationSeed("HOBBY_TRAVEL", "DATE_DRIVE", RelationType.COMPLEMENT),
      new RelationSeed("FOOD_DESSERT", "DATE_CAFE", RelationType.COMPLEMENT),
      new RelationSeed("FOOD_MEAT", "DATE_RESTAURANT", RelationType.COMPLEMENT),
      new RelationSeed("LIFE_SPONTANEOUS", "LIFE_PLANNED", RelationType.COMPLEMENT),
      new RelationSeed("LIFE_HOMEBODY", "DATE_HOME", RelationType.COMPLEMENT),
      new RelationSeed("LIFE_OUTGOING", "DATE_ACTIVITY", RelationType.COMPLEMENT),
      new RelationSeed("PERS_TALKATIVE", "PERS_LISTENER", RelationType.COMPLEMENT),
      new RelationSeed("PERS_SHY", "PERS_KIND", RelationType.COMPLEMENT),
      new RelationSeed("PERS_CALM", "PERS_LIVELY", RelationType.COMPLEMENT),
      new RelationSeed("CONTACT_WHEN_NEEDED", "CONTACT_SLOW_REPLY", RelationType.COMPLEMENT),
      new RelationSeed("CONTACT_SLOW_BOND", "CONTACT_COMFORTABLE", RelationType.COMPLEMENT)
  );
}
