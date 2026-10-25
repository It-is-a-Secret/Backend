package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.event.MemberWithdrawnEvent;
import com.blursome.blursome.member.exception.MemberErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {

  private static final Long MEMBER_ID = 1L;
  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);

  @Mock
  private MemberService memberService;

  @Mock
  private ChatRoomService chatRoomService;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  private MemberWithdrawalService withdrawalService;

  @BeforeEach
  void setUp() {
    withdrawalService = new MemberWithdrawalService(memberService, chatRoomService, eventPublisher,
        Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
  }

  @Test
  @DisplayName("회원 행을 잠가 탈퇴시키고, 대화를 끝낸 뒤, 커밋 후 정리용 이벤트를 발행한다")
  void withdraw_whenActiveMember_thenWithdrawsClosesRoomsAndPublishes() {
    // given
    Member member = Member.createOAuthMember(OAuthProvider.KAKAO, "kakao-1", "blur", null, null);
    given(memberService.findActiveMemberForUpdate(MEMBER_ID)).willReturn(member);

    // when
    withdrawalService.withdraw(MEMBER_ID);

    // then
    assertThat(member.isWithdrawn()).isTrue();
    assertThat(member.getWithdrawnAt()).isEqualTo(NOW);
    InOrder order = inOrder(memberService, chatRoomService, eventPublisher);
    order.verify(memberService).findActiveMemberForUpdate(MEMBER_ID);
    order.verify(chatRoomService).closeRoomsOnMemberWithdrawal(MEMBER_ID);
    order.verify(eventPublisher).publishEvent(new MemberWithdrawnEvent(MEMBER_ID));
  }

  @Test
  @DisplayName("이미 탈퇴했거나 정지된 회원이면 MEMBER_403_INACTIVE로 거부하고 아무것도 바꾸지 않는다")
  void withdraw_whenInactiveMember_thenRejects() {
    // given
    given(memberService.findActiveMemberForUpdate(MEMBER_ID))
        .willThrow(BaseException.from(MemberErrorCode.MEMBER_INACTIVE));

    // when & then
    assertThatThrownBy(() -> withdrawalService.withdraw(MEMBER_ID))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_403_INACTIVE");
    verify(chatRoomService, never()).closeRoomsOnMemberWithdrawal(any());
    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }
}
