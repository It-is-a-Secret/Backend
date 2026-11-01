package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blursome.blursome.global.secretmoa.SecretMoaClient;
import com.blursome.blursome.global.secretmoa.SecretMoaProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 더미 placeholder 자격증명만으로도 S3 빈이 네트워크 호출 없이 생성되는지 검증한다(이슈 #48 완료 기준).
 * 실제 S3 호출 검증은 #55 통합에서 수행한다. SecretMoa S3가 켜졌을 때 정적 키를 쓰지 않는지도 확인한다.
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

  private final S3Config s3Config = new S3Config(dummyProperties, secretMoa(false));

  static SecretMoaProperties secretMoa(boolean s3Enabled) {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    SecretMoaProperties.Target target = new SecretMoaProperties.Target(id, id);
    return new SecretMoaProperties(URI.create("http://127.0.0.1:8081"), s3Enabled ? "smk_test" : null, "test",
        new SecretMoaProperties.S3(s3Enabled, Duration.ofMinutes(30), Duration.ofMinutes(15), Duration.ofMinutes(8),
            target, target),
        new SecretMoaProperties.Db(false, null, null, Duration.ofMinutes(20), Duration.ofMinutes(3),
            Duration.ofSeconds(60)));
  }

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

  @Test
  @DisplayName("SecretMoa S3가 꺼져 있으면 비활성 라우팅이며 SecretMoa를 호출하지 않는다")
  @SuppressWarnings("unchecked")
  void routing_disabledByDefault() {
    ObjectProvider<SecretMoaClient> provider = mock(ObjectProvider.class);

    S3CredentialRouting routing = s3Config.s3CredentialRouting(provider, Clock.systemUTC());

    assertThat(routing.enabled()).isFalse();
    verifyNoInteractions(provider);
  }

  @Test
  @DisplayName("SecretMoa S3가 켜지면 정적 키가 비어 있어도 빈이 생성되고, 기본 자격증명은 항상 실패한다")
  void enabled_staticCredentialsDisabled() {
    S3Properties noKeys = new S3Properties("o", "v", "https://v/", "ap-northeast-2",
        Duration.ofMinutes(5), Duration.ofMinutes(5), "", "");
    S3Config config = new S3Config(noKeys, secretMoa(true));

    assertThat(config.s3Client()).isNotNull();
    assertThat(config.s3Presigner()).isNotNull();
    assertThatThrownBy(S3Config.STATIC_CREDENTIALS_DISABLED::resolveCredentials)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S3CredentialRouting");
  }
}
