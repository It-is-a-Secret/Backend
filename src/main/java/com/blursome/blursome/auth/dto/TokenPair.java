package com.blursome.blursome.auth.dto;

public record TokenPair(
    String accessToken,
    String refreshToken,
    long accessTtlSeconds,
    long refreshTtlSeconds
) {
}
