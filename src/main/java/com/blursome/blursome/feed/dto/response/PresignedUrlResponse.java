package com.blursome.blursome.feed.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Presigned PUT URL 발급 응답. 요청한 사진 순서대로 발급 결과를 담는다.
 *
 * <p>프론트는 각 {@link PresignedUrl#uploadUrl()}에 {@link PresignedUrl#requiredHeaders()}를 <b>그대로</b> 실어
 * 원본을 직접 PUT한다. 헤더가 누락되면 서명 불일치로 업로드가 실패한다.
 */
public record PresignedUrlResponse(List<PresignedUrl> images) {

  /**
   * 사진 1장의 Presigned PUT 발급 결과.
   *
   * @param uploadUrl 프론트가 원본을 PUT할 단기 Presigned URL
   * @param originalKey <b>originals(비공개 원본) 버킷의 객체 key</b>. #50 메타데이터 저장 API가 이 값을 그대로 받아
   *     {@code FeedImage.originalKey}로 저장하고, 블러본 {@code variantKey}는 이 key에서 결정론적으로 계산한다.
   * @param requiredHeaders PUT 시 반드시 함께 보내야 하는 헤더 맵({@code Content-Type},
   *     {@code x-amz-meta-blur-level}). 서명에 고정되어 있어 임의로 바꾸면 PUT이 실패한다.
   */
  public record PresignedUrl(
      String uploadUrl,
      String originalKey,
      Map<String, String> requiredHeaders
  ) {
  }
}
