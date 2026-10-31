package com.blursome.blursome.global.secretmoa;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * SecretMoa가 발급한 S3 임시 자격증명(STS 세션). 버킷 하나로 범위가 제한되며 회수할 수 없고
 * 만료를 기다린다.
 *
 * @param leaseId Lease ID (감사 추적용)
 * @param accessKeyId 임시 access key. {@link #toString()}에서 가린다
 * @param secretAccessKey 임시 secret key. {@link #toString()}에서 가린다
 * @param sessionToken STS 세션 토큰. {@link #toString()}에서 가린다
 * @param expiresAt 만료 시각
 * @param ttl 발급 시 TTL
 * @param bucket 이 자격증명이 접근할 수 있는 버킷
 * @param prefix 허용 prefix ({@code ""}면 버킷 전체)
 * @param writable 쓰기 허용 여부
 */
public record S3Lease(
    UUID leaseId,
    String accessKeyId,
    String secretAccessKey,
    String sessionToken,
    Instant expiresAt,
    Duration ttl,
    String bucket,
    String prefix,
    boolean writable
) {

  @Override
  public String toString() {
    return "S3Lease[leaseId=" + leaseId + ", accessKeyId=***, secretAccessKey=***, sessionToken=***, expiresAt="
        + expiresAt + ", ttl=" + ttl + ", bucket=" + bucket + ", prefix=" + prefix + ", writable=" + writable + "]";
  }
}
