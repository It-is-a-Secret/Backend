package com.blursome.blursome.global.secretmoa;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * SecretMoa가 발급한 DB 임시 계정. 블라썸은 접속 주소로 기존 {@code DB_URL}을 쓰므로
 * SecretMoa가 돌려주는 JDBC URL은 담지 않는다.
 *
 * @param leaseId 회수에 쓰는 Lease ID
 * @param username 임시 DB 계정명 ({@code sm_...})
 * @param password 임시 DB 비밀번호. {@link #toString()}에서 가린다
 * @param expiresAt 만료 시각. 이후 SecretMoa가 계정을 회수한다
 * @param ttl 발급 시 TTL
 */
public record DbLease(UUID leaseId, String username, String password, Instant expiresAt, Duration ttl) {

  @Override
  public String toString() {
    return "DbLease[leaseId=" + leaseId + ", username=" + username + ", password=***, expiresAt="
        + expiresAt + ", ttl=" + ttl + "]";
  }
}
