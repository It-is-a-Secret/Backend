package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 더미 placeholder 자격증명만으로도 S3 빈이 네트워크 호출 없이 생성되는지 검증한다(이슈 #48 완료 기준).
 * 실제 S3 호출 검증은 #55 통합에서 수행한다.
 */
class S3ConfigTest {

  private final S3Properties dummyProperties = new S3Properties(
      "blursome-originals-dummy",
      "blursome-variants-dummy",
      "https://blursome-variants-dummy.s3.ap-northeast-2.amazonaws.com/",
      "ap-northeast-2",
      Duration.ofMinutes(5),
      Duration.ofMinutes(5),
      "dummy",
      "dummy");

  private final S3Config s3Config = new S3Config(dummyProperties);

  @Test
  @DisplayName("더미 자격증명으로 S3Client 빈이 생성된다")
  void s3Client_isCreatedWithDummyCredentials() {
    assertThat(s3Config.s3Client()).isNotNull();
  }

  @Test
  @DisplayName("더미 자격증명으로 S3Presigner 빈이 생성된다")
  void s3Presigner_isCreatedWithDummyCredentials() {
    assertThat(s3Config.s3Presigner()).isNotNull();
  }
}
