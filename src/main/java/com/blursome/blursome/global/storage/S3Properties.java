package com.blursome.blursome.global.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 피드 이미지 스토리지(S3) 설정. 블러 파이프라인은 원본(비공개)/블러본(공개)을 서로 다른 버킷에 둔다.
 *
 * <p>퍼블릭 액세스 차단(BPA)이 <b>버킷 단위</b> 설정이라 한 버킷 내 prefix로 공개/비공개를 나눌 수 없어,
 * {@code originalsBucket}(완전 비공개)과 {@code variantsBucket}(GET만 공개)을 분리해서 바인딩한다.
 * 단일 {@code bucket} 프로퍼티로 묶지 않는다. (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §1·§5)
 *
 * @param originalsBucket 비공개 원본 버킷 이름
 * @param variantsBucket 공개 블러본 버킷 이름
 * @param variantsBaseUrl 블러본 공개 URL 조립용 base. 공개 URL = {@code variantsBaseUrl + variantKey}
 * @param region AWS 리전 (서울 {@code ap-northeast-2})
 * @param putExpiration Presigned PUT 만료 시간 (원본 업로드용, 기본 5분 — #49)
 * @param getExpiration Presigned GET 만료 시간 (원본 단계 공개용, 기본 5분 — #53)
 * @param accessKey AWS access key ({@code .env} 주입, IAM 역할 미사용)
 * @param secretKey AWS secret key ({@code .env} 주입)
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
    String originalsBucket,
    String variantsBucket,
    String variantsBaseUrl,
    String region,
    Duration putExpiration,
    Duration getExpiration,
    String accessKey,
    String secretKey
) {
}
