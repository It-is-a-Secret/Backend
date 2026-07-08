package com.blursome.blursome.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 실제 S3 자격증명/버킷·리전이 올바르게 설정됐고, IAM 권한이 블러 파이프라인 설계대로 최소 권한으로
 * 묶여 있는지 검증한다(이슈 #55 성격).
 *
 * <p>설계({@code FEED_IMAGE_BLUR_PIPELINE.md} §8-4·§8-5)상 백엔드 앱 자격증명의 기대 권한:
 *
 * <ul>
 *   <li><b>originals</b>(비공개 원본): 백엔드가 presigned PUT/GET을 발급 → {@code PutObject}/{@code GetObject} 필요,
 *       {@code DeleteObject}는 불필요(v1은 즉시 삭제하지 않고 Lifecycle/cleanup batch가 처리).
 *   <li><b>variants</b>(공개 블러본): 객체는 <b>Lambda</b>가 쓰고 백엔드는 쓰지 않음 → 앱 자격증명의 {@code PutObject}는
 *       <b>거부되어야</b> 정상(최소 권한). 공개 노출은 객체 URL(publicUrl)로 이뤄진다.
 * </ul>
 *
 * <p>실제 네트워크 호출·외부 의존이 있으므로 일반 빌드·CI에서는 항상 스킵된다. 직접 검증할 때만:
 *
 * <pre>
 *   ./gradlew test --tests "*S3RealConnectionVerifyTest" -Ds3.verify=true
 * </pre>
 *
 * <p>자격증명은 Spring 컨텍스트(MySQL/Redis 의존) 없이 읽도록 프로젝트 루트 {@code .env}를 직접 파싱한다.
 * originals 검증 객체는 <b>고정 키</b>를 덮어써, 삭제 권한이 없어도(=정리 불가) 재실행 시 객체가 쌓이지 않게 한다.
 */
@EnabledIfSystemProperty(named = "s3.verify", matches = "true")
class S3RealConnectionVerifyTest {

  /** 재실행해도 누적되지 않도록 고정 키를 덮어쓴다(DeleteObject 미허용 대비). */
  private static final String ORIGINALS_VERIFY_KEY = "verify/connection-check.txt";
  private static final String VARIANTS_VERIFY_KEY = "verify/connection-check.jpg";

  @Test
  @DisplayName("실제 S3 값/권한이 블러 파이프라인 설계대로 설정되어 있다")
  void verifyRealS3ConfigAndPermissions() {
    Map<String, String> env = loadEnv();
    String region = require(env, "S3_REGION");
    String originalsBucket = require(env, "S3_ORIGINALS_BUCKET");
    String variantsBucket = require(env, "S3_VARIANTS_BUCKET");
    String variantsBaseUrl = require(env, "S3_VARIANTS_BASE_URL");
    String accessKey = require(env, "S3_ACCESS_KEY");
    String secretKey = require(env, "S3_SECRET_KEY");

    System.out.println("[S3 검증] region=" + region);
    System.out.println("[S3 검증] originalsBucket=" + originalsBucket);
    System.out.println("[S3 검증] variantsBucket=" + variantsBucket);
    System.out.println("[S3 검증] variantsBaseUrl=" + variantsBaseUrl);
    System.out.println("[S3 검증] accessKey=" + mask(accessKey) + " (secretKey 길이=" + secretKey.length() + ")");

    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    byte[] payload = ("blursome s3 connection check @ " + Instant.now())
        .getBytes(StandardCharsets.UTF_8);

    try (S3Client s3 = S3Client.builder()
            .region(Region.of(region)).credentialsProvider(credentials).build();
        S3Presigner presigner = S3Presigner.builder()
            .region(Region.of(region)).credentialsProvider(credentials).build()) {

      // --- originals: PutObject + GetObject(HEAD/presign) 가능해야 한다 ---
      s3.putObject(
          PutObjectRequest.builder().bucket(originalsBucket).key(ORIGINALS_VERIFY_KEY)
              .contentType("text/plain").build(),
          RequestBody.fromBytes(payload));
      System.out.println("[S3 검증] (originals) PUT 성공 — PutObject 권한 OK");

      HeadObjectResponse head = s3.headObject(
          HeadObjectRequest.builder().bucket(originalsBucket).key(ORIGINALS_VERIFY_KEY).build());
      assertThat(head.contentLength()).isEqualTo((long) payload.length);
      System.out.println("[S3 검증] (originals) HEAD 성공 — GetObject 권한 OK (len=" + head.contentLength() + ")");

      var presigned = presigner.presignGetObject(
          GetObjectPresignRequest.builder()
              .signatureDuration(Duration.ofMinutes(5))
              .getObjectRequest(GetObjectRequest.builder()
                  .bucket(originalsBucket).key(ORIGINALS_VERIFY_KEY).build())
              .build());
      assertThat(presigned.url().toString()).contains(originalsBucket).contains(region);
      System.out.println("[S3 검증] (originals) presigned GET 발급 OK");

      // DeleteObject는 설계상 불필요 → 거부가 정상. 권한이 있으면(있어도 무방) 정리해 둔다.
      try {
        s3.deleteObject(
            DeleteObjectRequest.builder().bucket(originalsBucket).key(ORIGINALS_VERIFY_KEY).build());
        System.out.println("[S3 검증] (originals) DELETE 성공 — 검증 객체 정리됨(DeleteObject 권한 보유)");
      } catch (S3Exception e) {
        System.out.println("[S3 검증] (originals) DELETE 거부(HTTP " + e.statusCode()
            + ") — 설계상 정상. 고정 키 객체 1개 잔존: " + ORIGINALS_VERIFY_KEY);
      }

      // --- variants: 백엔드 자격증명의 PutObject는 거부되어야 정상(Lambda만 씀) ---
      assertThatThrownBy(() ->
          s3.putObject(
              PutObjectRequest.builder().bucket(variantsBucket).key(VARIANTS_VERIFY_KEY).build(),
              RequestBody.fromBytes(payload)))
          .isInstanceOf(S3Exception.class)
          .satisfies(ex -> assertThat(((S3Exception) ex).statusCode()).isEqualTo(403));
      System.out.println("[S3 검증] (variants) PUT 거부(403) 확인 — 최소 권한 정상(백엔드는 variants 비쓰기)");

      // 공개 URL 형태가 버킷/리전과 일관적인지
      String publicUrl = variantsBaseUrl + VARIANTS_VERIFY_KEY;
      assertThat(publicUrl).startsWith("https://").contains(variantsBucket).contains(region);
      System.out.println("[S3 검증] 블러본 공개 URL 형태 OK = " + publicUrl);
    }

    System.out.println("[S3 검증] ✅ S3 값·리전·자격증명이 올바르고, IAM 권한이 설계대로 설정되어 있습니다.");
  }

  /** 프로젝트 루트의 .env를 단순 파싱한다(KEY=VALUE, # 주석/빈 줄 무시). 없으면 빈 맵. */
  private Map<String, String> loadEnv() {
    Map<String, String> result = new HashMap<>();
    Path envPath = Path.of(System.getProperty("user.dir"), ".env");
    if (!Files.exists(envPath)) {
      return result;
    }
    try {
      for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        result.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
      }
    } catch (IOException e) {
      throw new IllegalStateException(".env 파싱 실패: " + envPath, e);
    }
    return result;
  }

  /** .env → OS 환경변수 순으로 값을 찾고, 더미/누락이면 명확히 실패시킨다. */
  private String require(Map<String, String> env, String key) {
    String value = env.getOrDefault(key, System.getenv(key));
    if (value == null || value.isBlank() || "dummy".equals(value) || value.endsWith("-dummy")) {
      throw new IllegalStateException(
          key + " 가 실제 값으로 설정되어 있지 않습니다(.env 또는 환경변수 확인). 현재=" + value);
    }
    return value;
  }

  private String mask(String value) {
    return value.length() <= 4 ? "****" : value.substring(0, 4) + "****";
  }
}
