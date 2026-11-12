package com.blursome.blursome.global.secretmoa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.blursome.blursome.global.secretmoa.SecretMoaException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** SecretMoa 클라이언트의 요청 형식, 응답 해석, 오류 매핑, 비밀값 비노출을 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class SecretMoaClientTest {

  private static final String BASE = "http://secretmoa.test";
  private static final String API_KEY = "smk_00000000-0000-0000-0000-000000000000.API-KEY-SECRET-abcdefghijklmnopqrstuvwxyz0";
  private static final String INSTANCE = "test-instance-01";
  private static final UUID DB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ROLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID LEASE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String REQUEST_ID = "44444444-4444-4444-4444-444444444444";

  private static final String DB_PASSWORD = "DB-PASSWORD-SECRET-0123456789abcdefghijklmn";
  private static final String S3_AKID = "ASIAAKIDSECRET0000001";
  private static final String S3_SECRET = "S3-SECRET-ACCESS-KEY-SECRET-0123456789";
  private static final String S3_TOKEN = "S3-SESSION-TOKEN-SECRET-abcdefghijklmnopqrstuvwxyz";

  private static final String DB_JSON = """
      {"leaseId":"%s","databaseId":"%s","roleId":"%s",
       "jdbcUrl":"jdbc:mysql://127.0.0.1:3307/test","username":"sm_0000000000000000000000test",
       "password":"%s","ttlSeconds":3600,"expiresAt":"2026-10-31T08:00:00Z",
       "renewable":false,"revocation":"immediate"}
      """.formatted(LEASE_ID, DB_ID, ROLE_ID, DB_PASSWORD);

  private static final String S3_JSON = """
      {"leaseId":"%s","targetId":"%s","roleId":"%s","bucket":"test-originals","region":"ap-northeast-2",
       "prefix":"","writable":true,"accessKeyId":"%s","secretAccessKey":"%s","sessionToken":"%s",
       "ttlSeconds":3599,"expiresAt":"2026-10-31T08:00:00Z","renewable":false,"revocation":"on-expiry"}
      """.formatted(LEASE_ID, DB_ID, ROLE_ID, S3_AKID, S3_SECRET, S3_TOKEN);

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private MockRestServiceServer server;
  private SecretMoaClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new SecretMoaClient(builder.build(), API_KEY, INSTANCE, objectMapper);
  }

  @Test
  @DisplayName("DB 발급: 경로·헤더가 맞고 응답을 DbLease로 해석한다")
  void issueDbCredential_requestAndParse() {
    server.expect(requestTo(BASE + "/v1/databases/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + API_KEY))
        .andExpect(header("X-Instance-Id", INSTANCE))
        .andRespond(withSuccess(DB_JSON, MediaType.APPLICATION_JSON));

    DbLease lease = client.issueDbCredential(DB_ID, ROLE_ID);

    server.verify();
    assertThat(lease.leaseId()).isEqualTo(LEASE_ID);
    assertThat(lease.username()).isEqualTo("sm_0000000000000000000000test");
    assertThat(lease.password()).isEqualTo(DB_PASSWORD);
    assertThat(lease.expiresAt()).isEqualTo(Instant.parse("2026-10-31T08:00:00Z"));
    assertThat(lease.ttl()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("DB 회수: Lease 경로로 POST하고 204를 성공으로 본다")
  void revokeDbLease_request() {
    server.expect(requestTo(BASE + "/v1/leases/" + LEASE_ID + "/revoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + API_KEY))
        .andExpect(header("X-Instance-Id", INSTANCE))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    client.revokeDbLease(LEASE_ID);

    server.verify();
  }

  @Test
  @DisplayName("S3 발급: 경로·헤더가 맞고 응답을 S3Lease로 해석한다")
  void issueS3Credential_requestAndParse() {
    server.expect(requestTo(BASE + "/v1/s3/targets/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + API_KEY))
        .andExpect(header("X-Instance-Id", INSTANCE))
        .andRespond(withSuccess(S3_JSON, MediaType.APPLICATION_JSON));

    S3Lease lease = client.issueS3Credential(DB_ID, ROLE_ID);

    server.verify();
    assertThat(lease.leaseId()).isEqualTo(LEASE_ID);
    assertThat(lease.accessKeyId()).isEqualTo(S3_AKID);
    assertThat(lease.secretAccessKey()).isEqualTo(S3_SECRET);
    assertThat(lease.sessionToken()).isEqualTo(S3_TOKEN);
    assertThat(lease.bucket()).isEqualTo("test-originals");
    assertThat(lease.prefix()).isEmpty();
    assertThat(lease.writable()).isTrue();
    assertThat(lease.ttl()).isEqualTo(Duration.ofSeconds(3599));
  }

  static Stream<Arguments> errorStatuses() {
    return Stream.of(
        Arguments.of(HttpStatus.UNAUTHORIZED, "unauthorized", Reason.UNAUTHORIZED, false),
        Arguments.of(HttpStatus.FORBIDDEN, "forbidden", Reason.FORBIDDEN, false),
        Arguments.of(HttpStatus.NOT_FOUND, "not_found", Reason.NOT_FOUND, false),
        Arguments.of(HttpStatus.CONFLICT, "revocation_on_expiry", Reason.CONFLICT, false),
        Arguments.of(HttpStatus.TOO_MANY_REQUESTS, "busy", Reason.BUSY, true),
        Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, "issuance_unavailable", Reason.UNAVAILABLE, true),
        Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", Reason.UNAVAILABLE, true),
        Arguments.of(HttpStatus.BAD_REQUEST, "invalid_request", Reason.UNEXPECTED_STATUS, false));
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @MethodSource("errorStatuses")
  @DisplayName("오류 상태를 Reason으로 매핑하고 오류 코드·요청 ID만 담는다")
  void errorStatus_mapping(HttpStatus status, String code, Reason reason, boolean retryable) {
    server.expect(requestTo(BASE + "/v1/databases/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", REQUEST_ID)
            .body("{\"error\":\"" + code + "\",\"detail\":\"BODY-SECRET-" + DB_PASSWORD + "\"}"));

    assertThatThrownBy(() -> client.issueDbCredential(DB_ID, ROLE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class, e -> {
          assertThat(e.reason()).isEqualTo(reason);
          assertThat(e.status()).isEqualTo(status.value());
          assertThat(e.errorCode()).isEqualTo(code);
          assertThat(e.requestId()).isEqualTo(REQUEST_ID);
          assertThat(e.isRetryable()).isEqualTo(retryable);
          assertThat(e.getCause()).isNull();
          assertThat(e.getMessage()).doesNotContain("BODY-SECRET").doesNotContain(DB_PASSWORD)
              .doesNotContain(API_KEY);
        });
  }

  @Test
  @DisplayName("형식에 맞지 않는 오류 코드·요청 ID는 버린다")
  void errorStatus_untrustedValuesDropped() {
    server.expect(requestTo(BASE + "/v1/leases/" + LEASE_ID + "/revoke"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", "bad id\r\nX-Injected: 1")
            .body("{\"error\":\"Password=" + DB_PASSWORD + "\"}"));

    assertThatThrownBy(() -> client.revokeDbLease(LEASE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.FORBIDDEN);
          assertThat(e.errorCode()).isNull();
          assertThat(e.requestId()).isNull();
          assertThat(e.getMessage()).doesNotContain(DB_PASSWORD).doesNotContain("Injected");
        });
  }

  @Test
  @DisplayName("오류 본문이 JSON이 아니어도 상태로 매핑한다")
  void errorStatus_nonJsonBody() {
    server.expect(requestTo(BASE + "/v1/leases/" + LEASE_ID + "/revoke"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("<html>" + DB_PASSWORD + "</html>"));

    assertThatThrownBy(() -> client.revokeDbLease(LEASE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
          assertThat(e.errorCode()).isNull();
          assertThat(e.getMessage()).doesNotContain(DB_PASSWORD);
        });
  }

  @Test
  @DisplayName("연결 실패·타임아웃은 UNREACHABLE이며 원인 예외를 붙이지 않는다")
  void ioFailure_unreachable() {
    server.expect(requestTo(BASE + "/v1/leases/" + LEASE_ID + "/revoke"))
        .andRespond(withException(new SocketTimeoutException("timeout " + API_KEY)));

    assertThatThrownBy(() -> client.revokeDbLease(LEASE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE);
          assertThat(e.isRetryable()).isTrue();
          assertThat(e.getCause()).isNull();
          assertThat(e.getMessage()).doesNotContain(API_KEY);
        });
  }

  @Test
  @DisplayName("2xx 응답이 깨진 JSON이면 INVALID_RESPONSE이며 응답 일부를 메시지에 넣지 않는다")
  void malformedSuccess_invalidResponse() {
    server.expect(requestTo(BASE + "/v1/s3/targets/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess("{\"secretAccessKey\":\"" + S3_SECRET + "\", broken", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.issueS3Credential(DB_ID, ROLE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE);
          assertThat(e.getCause()).isNull();
          assertThat(e.getMessage()).doesNotContain(S3_SECRET);
        });
  }

  @Test
  @DisplayName("2xx 응답에 필수 필드가 없으면 INVALID_RESPONSE")
  void missingField_invalidResponse() {
    server.expect(requestTo(BASE + "/v1/databases/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess("{\"leaseId\":\"" + LEASE_ID + "\",\"username\":\"sm_x\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.issueDbCredential(DB_ID, ROLE_ID))
        .isInstanceOfSatisfying(SecretMoaException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
  }

  @Test
  @DisplayName("Lease의 toString은 비밀값을 가린다")
  void leaseToString_masksSecrets() {
    server.expect(requestTo(BASE + "/v1/databases/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess(DB_JSON, MediaType.APPLICATION_JSON));
    server.expect(requestTo(BASE + "/v1/s3/targets/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess(S3_JSON, MediaType.APPLICATION_JSON));

    String db = client.issueDbCredential(DB_ID, ROLE_ID).toString();
    String s3 = client.issueS3Credential(DB_ID, ROLE_ID).toString();

    assertThat(db).doesNotContain(DB_PASSWORD).contains("password=***").contains(LEASE_ID.toString());
    assertThat(s3).doesNotContain(S3_AKID).doesNotContain(S3_SECRET).doesNotContain(S3_TOKEN)
        .contains("secretAccessKey=***");
  }

  @Test
  @DisplayName("성공·실패 로그에 API Key와 발급된 비밀값이 남지 않는다")
  void logs_containNoSecrets(CapturedOutput output) {
    server.expect(requestTo(BASE + "/v1/databases/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess(DB_JSON, MediaType.APPLICATION_JSON));
    server.expect(requestTo(BASE + "/v1/s3/targets/" + DB_ID + "/roles/" + ROLE_ID + "/credentials"))
        .andRespond(withSuccess(S3_JSON, MediaType.APPLICATION_JSON));
    server.expect(requestTo(BASE + "/v1/leases/" + LEASE_ID + "/revoke"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"issuance_unavailable\",\"x\":\"" + DB_PASSWORD + "\"}"));

    client.issueDbCredential(DB_ID, ROLE_ID);
    client.issueS3Credential(DB_ID, ROLE_ID);
    assertThatThrownBy(() -> client.revokeDbLease(LEASE_ID)).isInstanceOf(SecretMoaException.class);

    assertThat(output.getAll())
        .contains("SecretMoa DB credential issued", "SecretMoa S3 credential issued", "SecretMoa DB lease revoke failed")
        .contains(LEASE_ID.toString())
        .doesNotContain(API_KEY, DB_PASSWORD, S3_AKID, S3_SECRET, S3_TOKEN);
  }

  @Test
  @DisplayName("SecretMoaException 생성은 IOException 원인을 받지 않는다")
  void exceptionFactories_haveNoCause() {
    assertThat(SecretMoaException.unreachable("ResourceAccessException").getCause()).isNull();
    assertThat(SecretMoaException.invalidResponse("x").getCause()).isNull();
    assertThat(SecretMoaException.fromStatus(503, "issuance_unavailable", REQUEST_ID).getCause()).isNull();
  }
}
