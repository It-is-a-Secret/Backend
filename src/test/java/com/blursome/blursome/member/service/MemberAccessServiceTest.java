package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberAccessServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @InjectMocks
  private MemberAccessService memberAccessService;

  @Test
  @DisplayName("탈퇴한 회원이면 MEMBER_403_INACTIVE로 막는다(#107)")
  void validateAccess_whenWithdrawn_thenThrowsInactive() {
    // given
    given(memberRepository.existsByIdAndActivityStatus(1L, ActivityStatus.WITHDRAWN)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> memberAccessService.validateAccess(1L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_403_INACTIVE");
  }

  @Test
  @DisplayName("탈퇴하지 않은 회원이나 행이 없는 회원은 통과시킨다")
  void validateAccess_whenNotWithdrawn_thenPasses() {
    // given
    given(memberRepository.existsByIdAndActivityStatus(2L, ActivityStatus.WITHDRAWN)).willReturn(false);

    // when & then
    assertThatCode(() -> memberAccessService.validateAccess(2L)).doesNotThrowAnyException();
    assertThatCode(() -> memberAccessService.validateAccess(null)).doesNotThrowAnyException();
  }
}
