package com.blursome.blursome.keyword.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 키워드 카테고리 마스터(취미/여가, 음식 등 8종).
 *
 * <p>참조 데이터로, 운영·시드 마이그레이션으로 관리한다(설계 {@code docs/keyword/KEYWORD_DOMAIN.md}).
 * {@code code}는 프로그램·시드 참조용 안정 식별자, {@code name}은 표시명이며 둘 다 유니크다.
 * {@code required}는 온보딩에서 해당 카테고리를 최소 1개 선택해야 하는지를 나타낸다(핵심 카테고리만 true).
 */
@Entity
@Getter
@Table(
    name = "keyword_category",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_keyword_category_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_keyword_category_name", columnNames = "name")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KeywordCategory extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 40)
  private String code;

  @Column(nullable = false, length = 30)
  private String name;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "is_required", nullable = false)
  private boolean required;

  @Builder(access = AccessLevel.PRIVATE)
  private KeywordCategory(String code, String name, int sortOrder, boolean required) {
    this.code = code;
    this.name = name;
    this.sortOrder = sortOrder;
    this.required = required;
  }

  /** 카테고리 마스터 행을 생성한다(시드 전용). */
  public static KeywordCategory of(String code, String name, int sortOrder, boolean required) {
    return KeywordCategory.builder()
        .code(code)
        .name(name)
        .sortOrder(sortOrder)
        .required(required)
        .build();
  }
}
