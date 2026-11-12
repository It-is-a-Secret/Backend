package com.blursome.blursome.global.secretmoa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SecretMoa Application API 클라이언트. DB 임시 계정 발급·회수와 S3 임시 자격증명 발급만 다룬다.
 *
 * <p>S3 자격증명은 SecretMoa에서 회수할 수 없으므로(항상 409) 회수 메서드를 두지 않는다.
 *
 * <p>비밀값 보호: 응답은 이 클래스에서 직접 읽고 해석한다. RestClient 기본 오류 처리기는 응답 본문을
 * 예외 메시지에 넣기 때문에 쓰지 않는다. 로그에는 Lease ID, TTL, 소요 시간만 남긴다. HTTP 로깅
 * interceptor를 이 클라이언트에 붙이지 않는다.
 */
public class SecretMoaClient {

  private static final Logger log = LoggerFactory.getLogger(SecretMoaClient.class);

  static final String INSTANCE_ID_HEADER = "X-Instance-Id";
  static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final int MAX_ERROR_BODY_BYTES = 1024;

  private final RestClient restClient;
  private final String authorization;
  private final String instanceId;
  private final ObjectMapper objectMapper;

  /**
   * @param restClient base URL과 타임아웃이 설정된 클라이언트
   * @param apiKey Application API Key
   * @param instanceId 감사 로그용 인스턴스 식별자
   * @param objectMapper JSON 해석기 ({@link Instant} 지원 필요)
   */
  public SecretMoaClient(RestClient restClient, String apiKey, String instanceId, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.authorization = "Bearer " + apiKey;
    this.instanceId = instanceId;
    this.objectMapper = objectMapper;
  }

  /** DB 임시 계정을 발급한다. */
  public DbLease issueDbCredential(UUID databaseId, UUID roleId) {
    long started = System.nanoTime();
    try {
      DbCredentialResponse r = parse(
          post("/v1/databases/{databaseId}/roles/{roleId}/credentials", databaseId, roleId),
          DbCredentialResponse.class);
      if (r.leaseId() == null || isBlank(r.username()) || isBlank(r.password()) || r.expiresAt() == null) {
        throw SecretMoaException.invalidResponse("missing DB credential field");
      }
      DbLease lease = new DbLease(toUuid(r.leaseId()), r.username(), r.password(), r.expiresAt(),
          Duration.ofSeconds(r.ttlSeconds()));
      log.info("SecretMoa DB credential issued leaseId={} ttlSeconds={} elapsedMs={}",
          lease.leaseId(), r.ttlSeconds(), elapsedMs(started));
      return lease;
    } catch (SecretMoaException e) {
      log.warn("SecretMoa DB credential issue failed reason={} status={} requestId={} elapsedMs={}",
          e.reason(), e.status(), e.requestId(), elapsedMs(started));
      throw e;
    }
  }

  /** DB 임시 계정을 회수한다. SecretMoa가 계정을 잠그고 남은 세션을 끊은 뒤 삭제한다. */
  public void revokeDbLease(UUID leaseId) {
    long started = System.nanoTime();
    try {
      post("/v1/leases/{leaseId}/revoke", leaseId);
      log.info("SecretMoa DB lease revoked leaseId={} elapsedMs={}", leaseId, elapsedMs(started));
    } catch (SecretMoaException e) {
      log.warn("SecretMoa DB lease revoke failed leaseId={} reason={} status={} requestId={} elapsedMs={}",
          leaseId, e.reason(), e.status(), e.requestId(), elapsedMs(started));
      throw e;
    }
  }

  /** S3 임시 자격증명을 발급한다. */
  public S3Lease issueS3Credential(UUID targetId, UUID roleId) {
    long started = System.nanoTime();
    try {
      S3CredentialResponse r = parse(
          post("/v1/s3/targets/{targetId}/roles/{roleId}/credentials", targetId, roleId),
          S3CredentialResponse.class);
      if (r.leaseId() == null || isBlank(r.accessKeyId()) || isBlank(r.secretAccessKey())
          || isBlank(r.sessionToken()) || r.expiresAt() == null || isBlank(r.bucket())) {
        throw SecretMoaException.invalidResponse("missing S3 credential field");
      }
      S3Lease lease = new S3Lease(toUuid(r.leaseId()), r.accessKeyId(), r.secretAccessKey(), r.sessionToken(),
          r.expiresAt(), Duration.ofSeconds(r.ttlSeconds()), r.bucket(), r.prefix() == null ? "" : r.prefix(),
          r.writable());
      log.info("SecretMoa S3 credential issued leaseId={} bucket={} ttlSeconds={} elapsedMs={}",
          lease.leaseId(), lease.bucket(), r.ttlSeconds(), elapsedMs(started));
      return lease;
    } catch (SecretMoaException e) {
      log.warn("SecretMoa S3 credential issue failed reason={} status={} requestId={} elapsedMs={}",
          e.reason(), e.status(), e.requestId(), elapsedMs(started));
      throw e;
    }
  }

  private byte[] post(String uriTemplate, Object... uriVariables) {
    try {
      return restClient.post()
          .uri(uriTemplate, uriVariables)
          .header(HttpHeaders.AUTHORIZATION, authorization)
          .header(INSTANCE_ID_HEADER, instanceId)
          .accept(MediaType.APPLICATION_JSON)
          .exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (status >= 200 && status < 300) {
              return response.getBody().readAllBytes();
            }
            throw SecretMoaException.fromStatus(status, errorCode(response.getBody()),
                response.getHeaders().getFirst(REQUEST_ID_HEADER));
          });
    } catch (RestClientException e) {
      // 연결 실패·타임아웃. 원인 예외는 붙이지 않는다.
      throw SecretMoaException.unreachable(e.getClass().getSimpleName());
    }
  }

  /** 오류 본문에서 {@code {"error":"..."}}의 코드만 꺼낸다. 형식 검사는 예외 쪽에서 한다. */
  private String errorCode(InputStream body) {
    try {
      byte[] bytes = body.readNBytes(MAX_ERROR_BODY_BYTES);
      return objectMapper.readTree(bytes).path("error").asText(null);
    } catch (IOException | RuntimeException ignored) {
      return null;
    }
  }

  private <T> T parse(byte[] body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (IOException e) {
      throw SecretMoaException.invalidResponse("unreadable " + type.getSimpleName());
    }
  }

  private static UUID toUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw SecretMoaException.invalidResponse("leaseId is not a UUID");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static long elapsedMs(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DbCredentialResponse(
      String leaseId, String username, String password, long ttlSeconds, Instant expiresAt) {

    @Override
    public String toString() {
      return "DbCredentialResponse[leaseId=" + leaseId + "]";
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record S3CredentialResponse(
      String leaseId, String bucket, String prefix, boolean writable, String accessKeyId,
      String secretAccessKey, String sessionToken, long ttlSeconds, Instant expiresAt) {

    @Override
    public String toString() {
      return "S3CredentialResponse[leaseId=" + leaseId + ", bucket=" + bucket + "]";
    }
  }
}
