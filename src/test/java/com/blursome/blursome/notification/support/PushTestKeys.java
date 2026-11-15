package com.blursome.blursome.notification.support;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** 테스트용 P-256 키 생성·인코딩 도우미. 브라우저 구독 키(p256dh·auth)와 VAPID 키를 실제 형식으로 만든다. */
public final class PushTestKeys {

  private PushTestKeys() {
  }

  public static KeyPair generateP256() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 압축하지 않은 점(0x04 | X(32) | Y(32), 65바이트). */
  public static byte[] uncompressed(ECPublicKey key) {
    byte[] out = new byte[65];
    out[0] = 0x04;
    copyFixed(key.getW().getAffineX(), out, 1);
    copyFixed(key.getW().getAffineY(), out, 33);
    return out;
  }

  public static String publicKeyBase64Url(KeyPair keyPair) {
    return base64Url(uncompressed((ECPublicKey) keyPair.getPublic()));
  }

  /** VAPID 개인 키 형식(스칼라 32바이트 base64url). */
  public static String privateKeyBase64Url(KeyPair keyPair) {
    byte[] out = new byte[32];
    copyFixed(((ECPrivateKey) keyPair.getPrivate()).getS(), out, 0);
    return base64Url(out);
  }

  public static byte[] randomAuth() {
    byte[] auth = new byte[16];
    new SecureRandom().nextBytes(auth);
    return auth;
  }

  public static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void copyFixed(BigInteger value, byte[] out, int offset) {
    byte[] raw = value.toByteArray();
    int len = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - len, out, offset + 32 - len, len);
  }
}
