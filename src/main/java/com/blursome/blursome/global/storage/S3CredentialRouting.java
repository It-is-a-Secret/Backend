package com.blursome.blursome.global.storage;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * S3 요청마다 버킷에 맞는 자격증명 공급자를 지정한다. S3 클라이언트·프리사이너 Bean은 하나로 두고
 * 요청의 override 설정으로 자격증명만 바꾼다.
 *
 * <p>비활성(기본)이면 요청을 건드리지 않아 클라이언트의 정적 자격증명이 쓰인다. 활성이면 originals 요청에는
 * originals 공급자, variants 요청에는 variants 공급자만 붙인다. SecretMoa S3 자격증명은 버킷 하나로
 * 범위가 제한되므로 서로 바꿔 쓰면 403이 난다.
 */
public final class S3CredentialRouting implements AutoCloseable {

  private static final S3CredentialRouting DISABLED = new S3CredentialRouting(null, null);

  private final AwsCredentialsProvider originals;
  private final AwsCredentialsProvider variants;

  private S3CredentialRouting(AwsCredentialsProvider originals, AwsCredentialsProvider variants) {
    this.originals = originals;
    this.variants = variants;
  }

  /** 요청을 건드리지 않는 비활성 라우팅. */
  public static S3CredentialRouting disabled() {
    return DISABLED;
  }

  /** 버킷별 공급자를 지정하는 활성 라우팅. */
  public static S3CredentialRouting of(AwsCredentialsProvider originals, AwsCredentialsProvider variants) {
    if (originals == null || variants == null) {
      throw new IllegalArgumentException("both originals and variants providers are required");
    }
    return new S3CredentialRouting(originals, variants);
  }

  public boolean enabled() {
    return originals != null;
  }

  /** originals 버킷 요청에 originals 자격증명을 지정한다. 비활성이면 그대로 돌려준다. */
  public <B extends AwsRequest.Builder> B originals(B request) {
    return apply(request, originals);
  }

  /** variants 버킷 요청에 variants 자격증명을 지정한다. 비활성이면 그대로 돌려준다. */
  public <B extends AwsRequest.Builder> B variants(B request) {
    return apply(request, variants);
  }

  private static <B extends AwsRequest.Builder> B apply(B request, AwsCredentialsProvider provider) {
    if (provider != null) {
      request.overrideConfiguration(o -> o.credentialsProvider(provider));
    }
    return request;
  }

  @Override
  public void close() {
    closeQuietly(originals);
    closeQuietly(variants);
  }

  private static void closeQuietly(AwsCredentialsProvider provider) {
    if (provider instanceof SdkAutoCloseable closeable) {
      closeable.close();
    }
  }
}
