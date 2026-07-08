package com.blursome.blursome.keyword.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 태그 간 관계(유사/보완/충돌) 한 건.
 *
 * <p>순서 무관 유일 쌍을 보장하기 위해 항상 {@code tag_a_id < tag_b_id}로 정규화해 저장한다
 * ({@link #of(KeywordTag, KeywordTag, RelationType)}가 두 태그를 id 기준으로 정렬). 여기에
 * {@code UNIQUE(tag_a_id, tag_b_id)}를 더해 <b>한 쌍은 한 행·한 관계 유형</b>만 갖도록 강제한다
 * (검토 개선안 #2 보완형 중복 행, #3 보완↔충돌 양립 차단). 동일(+10)은 행이 아니라 회원 간 {@code tag_id}
 * 일치로 판정하므로 본 테이블에 동일 관계는 저장하지 않는다.
 */
@Entity
@Getter
@Table(
    name = "keyword_relation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_keyword_relation_pair",
        columnNames = {"tag_a_id", "tag_b_id"}
    )
)
@Check(name = "chk_keyword_relation_order", constraints = "tag_a_id < tag_b_id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KeywordRelation extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tag_a_id", nullable = false)
  private KeywordTag tagA;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tag_b_id", nullable = false)
  private KeywordTag tagB;

  @Enumerated(EnumType.STRING)
  @Column(name = "relation_type", nullable = false, length = 20)
  private RelationType relationType;

  @Builder(access = AccessLevel.PRIVATE)
  private KeywordRelation(KeywordTag tagA, KeywordTag tagB, RelationType relationType) {
    this.tagA = tagA;
    this.tagB = tagB;
    this.relationType = relationType;
  }

  /**
   * 두 태그의 관계 한 건을 생성한다. 인자 순서와 무관하게 {@code tag_a_id < tag_b_id}로 정규화해
   * 동일 쌍의 중복 행을 방지한다. 두 태그는 영속 상태(id 존재)여야 한다.
   *
   * @throws IllegalArgumentException 같은 태그이거나 id가 없는 경우
   */
  public static KeywordRelation of(KeywordTag a, KeywordTag b, RelationType relationType) {
    if (a.getId() == null || b.getId() == null) {
      throw new IllegalArgumentException("관계를 맺으려면 두 태그가 모두 영속 상태여야 합니다.");
    }
    if (a.getId().equals(b.getId())) {
      throw new IllegalArgumentException("같은 태그끼리는 관계를 만들 수 없습니다: " + a.getCode());
    }
    KeywordTag low = a.getId() < b.getId() ? a : b;
    KeywordTag high = a.getId() < b.getId() ? b : a;
    return KeywordRelation.builder()
        .tagA(low)
        .tagB(high)
        .relationType(relationType)
        .build();
  }
}
