package com.blursome.blursome.feed.repository;

import com.blursome.blursome.feed.domain.Feed;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedRepository extends JpaRepository<Feed, Long> {

  Optional<Feed> findByMemberId(Long memberId);
}
