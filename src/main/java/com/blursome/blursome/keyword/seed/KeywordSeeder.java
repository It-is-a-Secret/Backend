package com.blursome.blursome.keyword.seed;

import com.blursome.blursome.keyword.domain.KeywordCategory;
import com.blursome.blursome.keyword.domain.KeywordRelation;
import com.blursome.blursome.keyword.domain.KeywordTag;
import com.blursome.blursome.keyword.repository.KeywordCategoryRepository;
import com.blursome.blursome.keyword.repository.KeywordRelationRepository;
import com.blursome.blursome.keyword.repository.KeywordTagRepository;
import com.blursome.blursome.keyword.seed.KeywordSeedData.CategorySeed;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키워드 카탈로그·관계 시드를 애플리케이션 기동 시 적재한다(참조 데이터).
 *
 * <p>마이그레이션 도구(Flyway 등)를 쓰지 않는 현 구성에서, 비어 있을 때만 삽입하는 <b>멱등</b> 시더로
 * 구성한다. 카테고리/태그/관계 각각 {@code count() > 0}이면 건너뛰므로 재기동·재배포에 안전하다.
 * 테스트 등에서 끄려면 {@code blursome.keyword.seed.enabled=false}를 설정한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blursome.keyword.seed.enabled", havingValue = "true",
    matchIfMissing = true)
public class KeywordSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KeywordSeeder.class);

  private final KeywordCategoryRepository categoryRepository;
  private final KeywordTagRepository tagRepository;
  private final KeywordRelationRepository relationRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    seedCategoriesAndTags();
    seedRelations();
  }

  private void seedCategoriesAndTags() {
    if (categoryRepository.count() > 0) {
      return;
    }
    int sortOrder = 0;
    for (CategorySeed categorySeed : KeywordSeedData.CATEGORIES) {
      KeywordCategory category = categoryRepository.save(
          KeywordCategory.of(categorySeed.code(), categorySeed.name(), sortOrder++,
              categorySeed.required()));
      List<KeywordTag> tags = categorySeed.tags().stream()
          .map(tagSeed -> KeywordTag.of(category, tagSeed.code(), tagSeed.name()))
          .toList();
      tagRepository.saveAll(tags);
    }
    log.info("[KeywordSeeder] 카테고리/태그 시드 완료 (카테고리 {}개)",
        KeywordSeedData.CATEGORIES.size());
  }

  private void seedRelations() {
    if (relationRepository.count() > 0) {
      return;
    }
    Map<String, KeywordTag> tagByCode = tagRepository.findAll().stream()
        .collect(Collectors.toMap(KeywordTag::getCode, Function.identity()));

    List<KeywordRelation> relations = KeywordSeedData.RELATIONS.stream()
        .map(seed -> KeywordRelation.of(
            requireTag(tagByCode, seed.codeA()),
            requireTag(tagByCode, seed.codeB()),
            seed.type()))
        .toList();
    relationRepository.saveAll(relations);
    log.info("[KeywordSeeder] 관계 시드 완료 ({}건)", relations.size());
  }

  private KeywordTag requireTag(Map<String, KeywordTag> tagByCode, String code) {
    KeywordTag tag = tagByCode.get(code);
    if (tag == null) {
      throw new IllegalStateException("시드 관계가 참조하는 태그 코드가 존재하지 않습니다: " + code);
    }
    return tag;
  }
}
