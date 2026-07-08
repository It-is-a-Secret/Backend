package com.blursome.blursome.feed.dto.response;

import java.util.List;

/**
 * 본인 피드 이미지 관리 조회 응답. 공개 피드 조회와 달리 진행/실패를 보여주기 위해
 * {@code PROCESSING}/{@code FAILED} 상태도 함께 내려준다. 이미지 본문은 저장(full-replace) 응답과 동일한
 * 관리 관점 형태이므로 {@link FeedImageResponse.Image}를 재사용한다.
 *
 * <p>{@code FAILED}가 하나라도 있으면 {@code reuploadRequired=true}로 해당 사진 재업로드를 안내한다.
 * v1은 자동 재시도·Lambda 재트리거가 없으므로 재업로드는 사용자가 직접 수행한다.
 * (설계: {@code FEED_IMAGE_DOMAIN.md} §2-5)
 *
 * @param reuploadRequired 블러 생성에 실패한 사진이 있어 재업로드가 필요한지 여부
 * @param images 본인 피드 사진을 노출 순서(displayOrder)대로 담은 목록
 */
public record MyFeedImagesResponse(boolean reuploadRequired, List<FeedImageResponse.Image> images) {
}
