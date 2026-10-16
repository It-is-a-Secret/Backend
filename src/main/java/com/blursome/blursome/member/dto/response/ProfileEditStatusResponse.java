package com.blursome.blursome.member.dto.response;

import com.blursome.blursome.member.domain.ProfileEditHistory;
import com.blursome.blursome.member.domain.ProfileItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 프로필 항목별 30일 변경 제한 상태(#95). */
public record ProfileEditStatusResponse(
    ItemStatus nickName,
    ItemStatus mbti,
    ItemStatus keywords
) {

  public static ProfileEditStatusResponse of(ProfileEditHistory history, LocalDateTime now) {
    return new ProfileEditStatusResponse(
        ItemStatus.of(history, ProfileItem.NICKNAME, now),
        ItemStatus.of(history, ProfileItem.MBTI, now),
        ItemStatus.of(history, ProfileItem.KEYWORDS, now)
    );
  }

  /**
   * @param lastChangedAt    최근 변경 시각. 온보딩 이후 변경한 적이 없으면 {@code null}
   * @param nextChangeableAt 다음 변경 가능 시각({@code lastChangedAt + 30일}). 변경한 적이 없으면 {@code null}
   * @param changeable       지금 변경할 수 있는지
   */
  @Schema(name = "ProfileEditItemStatus", description = "프로필 항목 하나의 30일 변경 제한 상태")
  public record ItemStatus(
      LocalDateTime lastChangedAt,
      LocalDateTime nextChangeableAt,
      boolean changeable
  ) {

    static ItemStatus of(ProfileEditHistory history, ProfileItem item, LocalDateTime now) {
      return new ItemStatus(
          history.lastChangedAt(item),
          history.nextChangeableAt(item),
          history.isChangeable(item, now)
      );
    }
  }
}
