package com.blursome.blursome.feed.repository;

import com.blursome.blursome.feed.domain.Feed;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedRepository extends JpaRepository<Feed, Long> {

  Optional<Feed> findByMemberId(Long memberId);

  /**
   * 회원의 피드 행을 비관적 쓰기 락({@code SELECT ... FOR UPDATE})으로 조회한다(#105).
   *
   * <p>피드 사진 저장은 현재 목록을 읽고 비교해 유지·삭제·추가를 반영하므로, 같은 회원의 저장 요청 두 개가 동시에
   * 돌면 서로의 결과가 섞일 수 있다. 저장 경로가 항상 피드 행을 먼저 잠그면 요청이 직렬화되어 한 요청의 최종 목록만
   * 남는다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from Feed f where f.member.id = :memberId")
  Optional<Feed> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
