package com.blursome.blursome.notification.repository;

import com.blursome.blursome.notification.domain.PushSubscription;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

  Optional<PushSubscription> findByEndpointHash(String endpointHash);

  @Query("select s from PushSubscription s where s.member.id = :memberId")
  List<PushSubscription> findAllByMemberId(@Param("memberId") Long memberId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from PushSubscription s "
      + "where s.endpointHash = :endpointHash and s.member.id = :memberId")
  int deleteByEndpointHashAndMemberId(@Param("endpointHash") String endpointHash,
      @Param("memberId") Long memberId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from PushSubscription s where s.endpointHash = :endpointHash")
  int deleteByEndpointHash(@Param("endpointHash") String endpointHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from PushSubscription s where s.member.id = :memberId")
  int deleteAllByMemberId(@Param("memberId") Long memberId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from PushSubscription s where s.id in :ids")
  int deleteAllByIdIn(@Param("ids") Collection<Long> ids);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update PushSubscription s set s.lastSuccessAt = :now where s.id in :ids")
  int markDelivered(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);
}
