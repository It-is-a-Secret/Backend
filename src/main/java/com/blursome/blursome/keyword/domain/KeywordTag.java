package com.blursome.blursome.keyword.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 카테고리에 속한 사전 태그 마스터(운동, 산책 등).
 *
 * <p>참조 데이터로 시드 마이그레이션으로 관리한다. {@code code}는 관계 시드·프로그램 참조용 안정 식별자이며,
 * {@code name}은 카테고리 내에서 유니크한 표시명이다. {@code active}가 false면 온보딩 노출에서 제외한다.
 *
 * <p>외형 태그(강아지상 등)는 매칭 비대상이므로 본 도메인에서 다루지 않는다(별도 도메인으로 분리).
 */
@Entity
@Getter
@Table(
    name = "keyword_tag",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_keyword_tag_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_keyword_tag_category_name", columnNames = {"category_id", "name"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KeywordTag extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false)
  private KeywordCategory category;

  @Column(nullable = false, length = 60)
  private String code;

  @Column(nullable = false, length = 30)
  private String name;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Builder(access = AccessLevel.PRIVATE)
  private KeywordTag(KeywordCategory category, String code, String name, boolean active) {
    this.category = category;
    this.code = code;
    this.name = name;
    this.active = active;
  }

  /** 태그 마스터 행을 생성한다(시드 전용). 기본 활성. */
  public static KeywordTag of(KeywordCategory category, String code, String name) {
    return KeywordTag.builder()
        .category(category)
        .code(code)
        .name(name)
        .active(true)
        .build();
  }
}
