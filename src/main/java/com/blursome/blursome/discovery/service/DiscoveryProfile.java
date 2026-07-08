package com.blursome.blursome.discovery.service;

import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Mbti;
import java.util.Set;

/**
 * 점수 계산에 필요한 회원 프로필 단면(viewer/후보 공통). 카드 응답과 별개의 내부 계산용 값이다.
 *
 * @param mbti       MBTI(모름이면 {@code null} — 현재 스키마상 항상 non-null이나 재분배 로직 대비)
 * @param birthYear  출생연도(B 점수)
 * @param department 학과(D 점수)
 * @param tagIds     선택한 키워드 태그 id 집합(K 점수)
 */
public record DiscoveryProfile(
    Mbti mbti,
    int birthYear,
    Department department,
    Set<Long> tagIds
) {

}
