package com.blursome.blursome.feed.domain;

import com.blursome.blursome.global.persistence.BaseEntity;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 피드 엔티티. 온보딩 완료 시 회원 1:1로 생성되며, 다른 회원에게 노출되는 공개 프로필 정보를 담는다.
 *
 * <p>{@link Member}의 민감 정보(provider, providerId, email, schoolEmail, registrationStatus 등)를 노출하지
 * 않고 피드 조회를 수행하기 위해 분리한다. {@code nickName}은 {@code Member}와 중복 보유하며, 닉네임 변경 기능
 * 추가 시 동기화가 필요하다.
 *
 * <p>제약 조건은 {@code Member}의 해당 필드와 동일하게 유지한다(길이·null 여부·유니크).
 */
@Entity
@Getter
@Table(
    name = "feed",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_feed_member", columnNames = {"member_id"}),
        @UniqueConstraint(name = "uk_feed_nick_name", columnNames = {"nick_name"})
    },
    // 탐색(Discovery) 후보 필터의 주 조건인 이성(gender) 조회를 위한 보조 인덱스.
    indexes = @Index(name = "idx_feed_gender", columnList = "gender")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feed extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @Column(name = "nick_name", nullable = false, length = 30)
  private String nickName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Gender gender;

  @Column(name = "birth_year", nullable = false)
  private Integer birthYear;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private Department department;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 4)
  private Mbti mbti;

  @Builder(access = AccessLevel.PRIVATE)
  private Feed(Member member, String nickName, Gender gender,
      Integer birthYear, Department department, Mbti mbti) {
    this.member = member;
    this.nickName = nickName;
    this.gender = gender;
    this.birthYear = birthYear;
    this.department = department;
    this.mbti = mbti;
  }

  /**
   * 온보딩 완료 시 피드를 생성한다. 닉네임은 이미 세팅된 {@code member.getNickName()}에서 가져오고,
   * 공개 프로필(성별·생년·학과·MBTI)은 온보딩 요청 값을 직접 받는다(Member는 이 값을 보유하지 않음).
   * 호출 전 {@code member.completeOnboarding(nickName)}이 완료되어 있어야 한다.
   */
  public static Feed createOnOnboarding(
      Member member, Gender gender, Integer birthYear, Department department, Mbti mbti) {
    return Feed.builder()
        .member(member)
        .nickName(member.getNickName())
        .gender(gender)
        .birthYear(birthYear)
        .department(department)
        .mbti(mbti)
        .build();
  }

  /**
   * 닉네임을 갱신한다. Member.nickName 변경 시 동기화 목적으로 호출한다.
   * 실제 유니크 충돌 검증은 호출 측(Service)의 flush + 예외 변환이 담당한다.
   */
  public void updateNickName(String nickName) {
    this.nickName = nickName;
  }
}
