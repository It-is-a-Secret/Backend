package com.blursome.blursome.member.domain;

import com.blursome.blursome.global.exception.BaseException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 프로필 항목별 최근 변경 시각(#95). 항목마다 30일에 한 번만 변경할 수 있다.
 *
 * <p>온보딩 입력은 "변경"으로 치지 않으므로 최초 값은 모두 {@code null}이며, {@code null}이면 즉시 변경할 수 있다.
 * 불변 값 객체로, 변경 기록은 새 인스턴스를 반환한다.
 */
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProfileEditHistory {

  public static final Duration CHANGE_INTERVAL = Duration.ofDays(30);

  @Column(name = "nick_name_changed_at")
  private LocalDateTime nickNameChangedAt;

  @Column(name = "mbti_changed_at")
  private LocalDateTime mbtiChangedAt;

  @Column(name = "keywords_changed_at")
  private LocalDateTime keywordsChangedAt;

  public static ProfileEditHistory empty() {
    return new ProfileEditHistory(null, null, null);
  }

  /** 항목의 최근 변경 시각. 온보딩 이후 변경한 적이 없으면 {@code null}. */
  public LocalDateTime lastChangedAt(ProfileItem item) {
    return switch (item) {
      case NICKNAME -> nickNameChangedAt;
      case MBTI -> mbtiChangedAt;
      case KEYWORDS -> keywordsChangedAt;
    };
  }

  /** 항목을 다시 변경할 수 있게 되는 시각. 변경한 적이 없으면 {@code null}(즉시 가능). */
  public LocalDateTime nextChangeableAt(ProfileItem item) {
    LocalDateTime last = lastChangedAt(item);
    return last == null ? null : last.plus(CHANGE_INTERVAL);
  }

  public boolean isChangeable(ProfileItem item, LocalDateTime now) {
    LocalDateTime next = nextChangeableAt(item);
    return next == null || !now.isBefore(next);
  }

  /**
   * 항목이 지금 변경 가능한지 검증한다.
   *
   * @throws BaseException 마지막 변경 후 30일이 지나지 않은 경우(항목별 에러코드)
   */
  public void validateChangeable(ProfileItem item, LocalDateTime now) {
    if (!isChangeable(item, now)) {
      throw BaseException.from(item.getChangeLimitedError());
    }
  }

  /** 항목의 변경 시각을 {@code now}로 기록한 새 이력을 반환한다. 제한 검증은 호출 측이 먼저 수행한다. */
  public ProfileEditHistory record(ProfileItem item, LocalDateTime now) {
    return switch (item) {
      case NICKNAME -> new ProfileEditHistory(now, mbtiChangedAt, keywordsChangedAt);
      case MBTI -> new ProfileEditHistory(nickNameChangedAt, now, keywordsChangedAt);
      case KEYWORDS -> new ProfileEditHistory(nickNameChangedAt, mbtiChangedAt, now);
    };
  }
}
