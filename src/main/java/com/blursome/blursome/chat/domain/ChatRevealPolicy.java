package com.blursome.blursome.chat.domain;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 사진 공개 단계 정책(이슈 #79). 유효 메시지 누적 수로 단계를 올리는 규칙의 단일 출처다. 임계값·최소 길이·
 * 디바운스를 전역 상수로 캡슐화하고, 양방향 최소 누적 카운트({@code min(A, B)})를 {@link ChatRoomProgressStatus}로
 * 매핑한다.
 *
 * <p>v1은 <b>전역 상수</b>이며 방별 가변 정책(DB 저장)은 지원하지 않는다. 임계값/규칙을 바꾸려면 이 클래스만
 * 고치면 된다. 설정 파일화({@code application.yml})는 필요해지면 추가한다.
 */
@Component
public class ChatRevealPolicy {

  /** 유효 메시지로 카운트되기 위한 {@code trim} 후 최소 글자 수. 길이는 <b>카운트 조건</b>일 뿐 송신 거부 조건이 아니다. */
  public static final int MIN_VALID_LENGTH = 4;

  /** 같은 발신자의 연속 유효 카운트 사이 최소 간격(발신자별 디바운스). */
  public static final Duration COUNT_DEBOUNCE = Duration.ofSeconds(2);

  /**
   * k번째(1-base) 단계 공개에 필요한 누적 유효 메시지 수. {@code THRESHOLDS[0]} → STEP_1(10) … {@code THRESHOLDS[4]}
   * → COMPLETED(50). 단계 수가 사진 장수 상한(5)과 1:1로 정합한다.
   */
  private static final int[] THRESHOLDS = {10, 20, 30, 40, 50};

  /** {@code trim} 후 길이가 최소 기준({@value #MIN_VALID_LENGTH}) 이상이면 카운트 가능한 콘텐츠다(TEXT 전제). */
  public boolean isCountableContent(String content) {
    return content != null && content.trim().length() >= MIN_VALID_LENGTH;
  }

  /** 발신자별 유효 카운트 디바운스 간격. */
  public Duration countDebounce() {
    return COUNT_DEBOUNCE;
  }

  /**
   * 양방향 최소 누적 카운트로 도달 가능한 공개 단계를 계산한다. {@code min < 10}이면 {@code MATCHED}, 임계값을
   * 넘긴 개수만큼 단계가 오르고 {@code min >= 50}이면 {@code COMPLETED}다.
   *
   * <p>선언 순서(ordinal)가 곧 단계 순서이므로 넘긴 임계값 개수를 그대로 enum 인덱스로 쓴다(MATCHED=0 …
   * COMPLETED=5). enum 값 순서 변경·중간 삽입 금지(append만 허용).
   *
   * @param minValidCount {@code min(A_valid_count, B_valid_count)}
   * @return 도달 가능한 공개 단계
   */
  public ChatRoomProgressStatus statusFor(long minValidCount) {
    int reached = 0;
    for (int threshold : THRESHOLDS) {
      if (minValidCount >= threshold) {
        reached++;
      }
    }
    return ChatRoomProgressStatus.values()[reached];
  }
}
