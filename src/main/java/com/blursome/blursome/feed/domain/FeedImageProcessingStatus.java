package com.blursome.blursome.feed.domain;

/**
 * 피드 이미지 블러본 생성 상태. 블러본은 Lambda가 비동기로 생성하므로, 메타데이터 저장 직후에는
 * {@code variantKey}가 가리키는 객체가 아직 S3에 없을 수 있다. 백엔드 스케줄러가 {@code HeadObject}로
 * 존재를 폴링해 상태를 전이시킨다.
 *
 * @see FeedImage
 */
public enum FeedImageProcessingStatus {

  /** 메타데이터 저장 직후 기본값. 블러본 미확인. */
  PROCESSING,

  /** HeadObject 200 — 블러본 존재 확인, 공개 노출 가능. */
  READY,

  /** created_at + 5분 초과까지 블러본 미확인. */
  FAILED
}
