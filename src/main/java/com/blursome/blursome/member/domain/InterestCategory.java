package com.blursome.blursome.member.domain;

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

/**
 * 회원이 온보딩에서 선택한 관심사 한 건.
 *
 * <p>한 회원이 여러 관심사를 가질 수 있으므로 {@code member_id}로 회원을 참조하는 별도 행으로
 * 저장한다. {@code name}은 고정 카탈로그 {@link InterestCategoryType}(STRING 저장)이며,
 * {@code description}은 선택 시점의 카탈로그 설명을 비정규화해 보관한다.
 *
 * <p>같은 회원이 같은 관심사를 중복 선택하지 못하도록 {@code (member_id, name)} 유니크 제약을
 * 둔다. 스키마 원본 DDL에는 없으나 감사 필드({@code createdAt}/{@code updatedAt})는 프로젝트
 * 컨벤션({@link BaseEntity})에 따라 함께 둔다. 설계 근거는 {@code docs/member/MEMBER_ONBOARDING.md}.
 */
@Entity
@Getter
@Table(
    name = "interest_category",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_interest_category_member",
        columnNames = {"member_id", "name"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestCategory extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "name", nullable = false, length = 30)
  private InterestCategoryType name;

  @Column(name = "description", length = 200)
  private String description;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @Builder(access = AccessLevel.PRIVATE)
  private InterestCategory(Member member, InterestCategoryType name, String description) {
    this.member = member;
    this.name = name;
    this.description = description;
  }

  /**
   * 회원의 관심사 한 건을 생성한다. {@code description}이 비어 있으면 카탈로그 기본 설명을 채운다.
   */
  public static InterestCategory of(Member member, InterestCategoryType name, String description) {
    String resolvedDescription =
        (description == null || description.isBlank()) ? name.getDescription() : description;
    return InterestCategory.builder()
        .member(member)
        .name(name)
        .description(resolvedDescription)
        .build();
  }
}
