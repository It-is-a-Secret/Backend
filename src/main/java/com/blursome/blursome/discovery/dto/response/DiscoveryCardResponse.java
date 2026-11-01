package com.blursome.blursome.discovery.dto.response;

import com.blursome.blursome.chat.service.PartnerRoom;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 탐색 목록의 카드 한 건. 다른 회원에게 노출되는 공개 프로필({@link Feed})과, 이미 대화 중이면 그 채팅방
 * ({@code chatRoom}, #112)을 담는다.
 *
 * <p>목록은 가중 점수순으로 정렬되며 page/size로 페이지네이션한다(Phase B). 이미 대화 중인 상대는 목록 뒤쪽에 온다.
 * 대표 블러 썸네일은 전부-READY 게이트·배치 조회가 필요해 후속에서 추가한다.
 */
public record DiscoveryCardResponse(
    Long feedId,
    String nickName,
    Integer birthYear,
    Department department,
    String departmentLabel,
    Mbti mbti,
    Gender gender,
    @Schema(description = "이 상대와 이미 있는 채팅방(#112). 있으면 첫 메시지 입력 없이 roomId로 바로 이동한다. "
        + "없으면 null", nullable = true)
    DiscoveryChatRoomResponse chatRoom
) {

  /** 채팅방이 없는 카드. */
  public static DiscoveryCardResponse from(Feed feed) {
    return from(feed, null);
  }

  /** @param room 이 상대와 이미 있는 채팅방. 없으면 null */
  public static DiscoveryCardResponse from(Feed feed, PartnerRoom room) {
    return new DiscoveryCardResponse(
        feed.getId(),
        feed.getNickName(),
        feed.getBirthYear(),
        feed.getDepartment(),
        feed.getDepartment().getLabel(),
        feed.getMbti(),
        feed.getGender(),
        DiscoveryChatRoomResponse.from(room)
    );
  }
}
