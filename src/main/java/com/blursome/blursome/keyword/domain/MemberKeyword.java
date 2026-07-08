package com.blursome.blursome.keyword.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Member;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 온보딩에서 선택한 태그 한 건(기존 {@code InterestCategory} 대체).
 *
 * <p>한 회원이 여러 태그를 선택하므로 {@code (member, tag)} 매핑 행으로 저장한다.
 * {@code UNIQUE(member_id, tag_id)}로 같은 태그 중복 선택을 차단하고, {@code tag_id} 인덱스로
 * 키워드 점수 계산 시 태그 기준 역조회를 지원한다.
 */
@Entity
@Getter
@Table(
    name = "member_keyword",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_member_keyword",
        columnNames = {"member_id", "tag_id"}
    ),
    indexes = @Index(name = "idx_member_keyword_tag", columnList = "tag_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberKeyword extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tag_id", nullable = false)
  private KeywordTag tag;

  @Builder(access = AccessLevel.PRIVATE)
  private MemberKeyword(Member member, KeywordTag tag) {
    this.member = member;
    this.tag = tag;
  }

  /** 회원의 태그 선택 한 건을 생성한다. */
  public static MemberKeyword of(Member member, KeywordTag tag) {
    return MemberKeyword.builder()
        .member(member)
        .tag(tag)
        .build();
  }
}
