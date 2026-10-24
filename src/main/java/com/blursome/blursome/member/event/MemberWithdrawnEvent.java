package com.blursome.blursome.member.event;

/**
 * 회원 탈퇴가 처리됐을 때 발행되는 도메인 이벤트(#107).
 *
 * <p>Redis에 남은 Refresh Token·학교 이메일 인증 코드처럼 DB 트랜잭션 밖의 상태를 정리하는 seam이다. DB 롤백과 어긋나지
 * 않도록 리스너는 {@code @TransactionalEventListener(AFTER_COMMIT)}로 받는다. 정리가 실패해도 Refresh API는 회원 상태를
 * 다시 확인하므로 탈퇴 회원에게 토큰이 새로 발급되지 않는다.
 *
 * @param memberId 탈퇴한 회원 id
 */
public record MemberWithdrawnEvent(Long memberId) {
}
