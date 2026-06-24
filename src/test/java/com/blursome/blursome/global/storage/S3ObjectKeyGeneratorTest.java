package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class S3ObjectKeyGeneratorTest {

  private final S3ObjectKeyGenerator generator = new S3ObjectKeyGenerator();

  @Test
  @DisplayName("원본 키는 originals/{memberId}/{uuid}.{ext} 형식이다")
  void generateOriginalKey_followsFormat() {
    String key = generator.generateOriginalKey(7L, "png");

    assertThat(key).matches("originals/7/[0-9a-f\\-]{36}\\.png");
  }

  @Test
  @DisplayName("호출마다 uuid가 달라 서로 다른 원본 키를 생성한다")
  void generateOriginalKey_isUnique() {
    String first = generator.generateOriginalKey(7L, "jpg");
    String second = generator.generateOriginalKey(7L, "jpg");

    assertThat(first).isNotEqualTo(second);
  }

  @ParameterizedTest(name = "확장자 {0} → {1}")
  @CsvSource({"JPG, jpg", ".PNG, png", "  jpeg  , jpeg"})
  @DisplayName("확장자는 점 제거·소문자·공백 제거로 정규화된다")
  void generateOriginalKey_normalizesExtension(String input, String expectedExt) {
    String key = generator.generateOriginalKey(7L, input);

    assertThat(key).endsWith("." + expectedExt);
  }

  @Test
  @DisplayName("원본 키로부터 결정론적으로 블러본 키를 계산한다(확장자 jpg 고정)")
  void toVariantKey_derivesDeterministically() {
    String originalKey = "originals/42/abc-123.png";

    String variantKey = generator.toVariantKey(originalKey);

    assertThat(variantKey).isEqualTo("variants/42/abc-123.jpg");
  }

  @Test
  @DisplayName("생성한 원본 키는 같은 memberId·uuid의 블러본 키로 변환된다")
  void generateThenToVariant_preservesMemberAndUuid() {
    String originalKey = generator.generateOriginalKey(99L, "jpeg");

    String variantKey = generator.toVariantKey(originalKey);

    String uuid = originalKey.substring("originals/99/".length(), originalKey.lastIndexOf('.'));
    assertThat(variantKey).isEqualTo("variants/99/" + uuid + ".jpg");
  }

  @Test
  @DisplayName("원본 키 형식이 아니면 예외를 던진다")
  void toVariantKey_rejectsNonOriginalKey() {
    assertThatThrownBy(() -> generator.toVariantKey("variants/1/x.jpg"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> generator.toVariantKey(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> generator.toVariantKey("originals/1/noext"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("생성한 원본 키는 같은 회원 소유로 인정된다")
  void isOwnedOriginalKey_acceptsGeneratedKey() {
    String key = generator.generateOriginalKey(7L, "png");

    assertThat(generator.isOwnedOriginalKey(7L, key)).isTrue();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource({
      // 다른 회원 prefix (IDOR)
      "originals/8/11111111-1111-1111-1111-111111111111.png",
      // variants 버킷 key
      "variants/7/11111111-1111-1111-1111-111111111111.jpg",
      // uuid가 아닌 임의 파일명
      "originals/7/evil.png",
      // 확장자 없음
      "originals/7/11111111-1111-1111-1111-111111111111",
      // 경로 구분자 끼워넣기
      "originals/7/sub/11111111-1111-1111-1111-111111111111.png"
  })
  @DisplayName("다른 회원 prefix·형식 위반 key는 소유로 인정하지 않는다")
  void isOwnedOriginalKey_rejectsInvalid(String key) {
    assertThat(generator.isOwnedOriginalKey(7L, key)).isFalse();
  }

  @Test
  @DisplayName("memberId나 key가 null이면 소유로 인정하지 않는다")
  void isOwnedOriginalKey_rejectsNull() {
    assertThat(generator.isOwnedOriginalKey(null, "originals/7/x.png")).isFalse();
    assertThat(generator.isOwnedOriginalKey(7L, null)).isFalse();
  }
}
