package com.blursome.blursome.feed.service;

import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피드 도메인 서비스. 피드 생성과 향후 피드 조회/수정 로직을 담당한다.
 *
 * <p>피드 생성({@link #createFeed})은 온보딩 완료 트랜잭션({@code MemberOnboardingService})에 합류하여
 * 원자적으로 처리된다. 온보딩이 롤백되면 피드 생성도 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedService {

  private final FeedRepository feedRepository;
  private final FeedImageRepository feedImageRepository;

  /**
   * 온보딩 완료 시점에 피드를 생성한다. 닉네임은 {@code member}에서, 공개 프로필(성별·생년·학과·MBTI)은
   * 온보딩 요청 값을 직접 받는다. 호출 전 {@code member.completeOnboarding(nickName)}이 완료되어 있어야 하며,
   * 호출 측 트랜잭션에 합류하므로 온보딩 트랜잭션과 원자적으로 처리된다.
   */
  @Transactional
  public Feed createFeed(
      Member member, Gender gender, Integer birthYear, Department department, Mbti mbti) {
    return feedRepository.save(Feed.createOnOnboarding(member, gender, birthYear, department, mbti));
  }

  /** 회원의 피드를 조회한다. 온보딩 미완료 회원은 피드가 없으므로 {@code Optional.empty()}. */
  public Optional<Feed> findFeedByMemberId(Long memberId) {
    return feedRepository.findByMemberId(memberId);
  }

  /** 피드 id로 피드를 조회한다. 없으면 {@code Optional.empty()}. */
  public Optional<Feed> findFeedById(Long feedId) {
    return feedRepository.findById(feedId);
  }

  /**
   * 피드가 다른 회원에게 공개될 수 있는지(사진 정확히 5장 전부 READY) 판정한다(#72 게이트, {@link FeedImage#isPublishable}).
   * 탐색 후보 질의가 JPQL로 강제하는 같은 규칙을 단건 검증(예: 대화 시작 게이트, 이슈 #87)에 재사용하기 위한 진입점이다.
   */
  public boolean isFeedPublishable(Long feedId) {
    return FeedImage.isPublishable(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId));
  }
}
