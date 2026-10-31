package com.blursome.blursome.global.secretmoa;

/** 다른 패키지 테스트에서 SecretMoa 실패를 흉내 낼 때 쓴다. 생성 메서드는 운영 코드에서 공개하지 않는다. */
public final class SecretMoaTestErrors {

  private SecretMoaTestErrors() {
  }

  /** 503 issuance_unavailable (재시도 가능). */
  public static SecretMoaException unavailable() {
    return SecretMoaException.fromStatus(503, "issuance_unavailable", null);
  }
}
