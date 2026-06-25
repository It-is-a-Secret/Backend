package com.blursome.blursome.discovery.service;

import com.blursome.blursome.keyword.domain.RelationType;
import com.blursome.blursome.keyword.repository.KeywordRelationRepository;
import com.blursome.blursome.keyword.repository.KeywordRelationRepository.RelationPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 태그 간 관계({@code keyword_relation})를 메모리에 적재해 빠르게 조회한다(설계 §17.4 메모리 캐시).
 *
 * <p>관계는 시드 이후 거의 변하지 않는 참조 데이터이므로 최초 접근 시 1회 적재해 앱 수명 동안 유지한다.
 * 관계를 바꾸면 재기동으로 반영한다(런타임 갱신이 필요해지면 무효화 훅을 추가).
 */
@Component
@RequiredArgsConstructor
public class KeywordRelationCache implements RelationLookup {

  private final KeywordRelationRepository keywordRelationRepository;

  private volatile Map<Long, RelationType> cache;

  @Override
  public RelationType find(long tagId1, long tagId2) {
    if (tagId1 == tagId2) {
      return null;
    }
    return ensureLoaded().get(pairKey(tagId1, tagId2));
  }

  private Map<Long, RelationType> ensureLoaded() {
    Map<Long, RelationType> local = cache;
    if (local == null) {
      synchronized (this) {
        local = cache;
        if (local == null) {
          local = load();
          cache = local;
        }
      }
    }
    return local;
  }

  private Map<Long, RelationType> load() {
    List<RelationPair> pairs = keywordRelationRepository.findAllPairs();
    Map<Long, RelationType> map = new HashMap<>();
    for (RelationPair pair : pairs) {
      map.put(pairKey(pair.getTagAId(), pair.getTagBId()), pair.getRelationType());
    }
    return map;
  }

  /** 순서 무관 키. 태그 id는 32비트에 충분히 들어가므로 (작은id << 32 | 큰id)로 합친다. */
  private static long pairKey(long a, long b) {
    long lo = Math.min(a, b);
    long hi = Math.max(a, b);
    return (lo << 32) | hi;
  }
}
