package com.blursome.blursome.member.scheduler;

import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.member.service.MemberService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 후 30일이 지난 회원의 카카오 식별자·학교 이메일·개인정보를 해제(익명화)하는 정리 배치(#107).
 *
 * <p>재가입 가능 여부의 정확성은 이 배치에 의존하지 않는다. 로그인·학교 이메일 인증 경로가 30일이 지난 탈퇴 회원을 만나면 그
 * 자리에서 해제하므로(lazy cleanup), 배치가 늦어도 재가입이 막히지 않는다. 배치는 원문 개인정보를 필요 이상 오래 보관하지 않도록
 * 주기적으로 정리하는 역할이다. 회원 행 자체는 채팅·신고 등 FK 이력 보존을 위해 남긴다.
 *
 * <p>1시간마다 대상을 오래된 순으로 최대 100명씩 처리하며, 회원마다 짧은 트랜잭션으로 해제해 로그인·인증과 오래 겹치지 않게 한다.
 * 단일 서버 기준 단순 {@code @Scheduled}다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberCleanupScheduler {

  private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;
  private static final Limit BATCH_LIMIT = Limit.of(100);

  private final MemberRepository memberRepository;
  private final MemberService memberService;
  private final Clock clock;

  @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
  public void releaseExpiredWithdrawnMembers() {
    LocalDateTime threshold = LocalDateTime.now(clock).minus(Member.REJOIN_RESTRICTION);
    List<Long> targetIds = memberRepository.findReleasableWithdrawnMembers(threshold, BATCH_LIMIT)
        .stream()
        .map(Member::getId)
        .toList();

    int released = 0;
    for (Long memberId : targetIds) {
      try {
        if (memberService.releaseExpiredWithdrawnMember(memberId)) {
          released++;
        }
      } catch (RuntimeException e) {
        // 한 명의 실패가 나머지 정리를 막지 않게 한다. 남은 회원은 다음 주기 또는 재가입 시점에 다시 해제된다.
        log.warn("탈퇴 회원 식별자 해제 실패. memberId={}", memberId, e);
      }
    }
    if (released > 0) {
      log.info("탈퇴 후 30일이 지난 회원 식별자 해제: {}명", released);
    }
  }
}
