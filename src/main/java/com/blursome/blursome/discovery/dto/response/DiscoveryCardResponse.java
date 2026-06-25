package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;

/**
 * 탐색 목록의 카드 한 건. 다른 회원에게 노출되는 공개 프로필({@link Feed})만 담는다.
 *
 * <p>{@code feedId}는 커서 페이지네이션의 커서로 그대로 사용한다(목록은 최신순=feedId 내림차순).
 * 대표 블러 썸네일은 Phase A 범위에서 제외하며(전부-READY 게이트·배치 조회 필요), 후속에서 추가한다.
 */
public record DiscoveryCardResponse(
    Long feedId,
    String nickName,
    Integer birthYear,
    Department department,
    String departmentLabel,
    Mbti mbti,
    Gender gender
) {

  public static DiscoveryCardResponse from(Feed feed) {
    return new DiscoveryCardResponse(
        feed.getId(),
        feed.getNickName(),
        feed.getBirthYear(),
        feed.getDepartment(),
        feed.getDepartment().getLabel(),
        feed.getMbti(),
        feed.getGender()
    );
  }
}
