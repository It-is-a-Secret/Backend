package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;

/**
 * 탐색 목록의 카드 한 건. 다른 회원에게 노출되는 공개 프로필({@link Feed})만 담는다.
 *
 * <p>목록은 가중 점수순으로 정렬되며 page/size로 페이지네이션한다(Phase B). 대표 블러 썸네일은
 * 전부-READY 게이트·배치 조회가 필요해 후속에서 추가한다.
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
