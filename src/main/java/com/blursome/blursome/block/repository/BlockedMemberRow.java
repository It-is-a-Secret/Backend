package com.blursome.blursome.block.repository;

import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Mbti;
import java.time.LocalDateTime;

/**
 * 차단 목록 조회 결과 한 행(#118). 차단 대상 회원과 그 공개 프로필({@code Feed})을 한 번의 쿼리로 읽기 위한 projection.
 *
 * <p>온보딩 전 회원은 피드가 없어 {@code birthYear}·{@code department}·{@code mbti}가 {@code null}이다.
 */
public record BlockedMemberRow(
    Long blockedMemberId,
    String nickName,
    Integer birthYear,
    Department department,
    Mbti mbti,
    LocalDateTime blockedAt
) {

}
