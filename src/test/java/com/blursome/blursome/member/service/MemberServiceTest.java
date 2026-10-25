package com.blursome.blursome.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.domain.RegistrationStatus;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);
  private static final String SCHOOL_EMAIL = "student@gs.anyang.ac.kr";

  @Mock
  private MemberRepository memberRepository;

  private MemberService memberService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    memberService = new MemberService(memberRepository, clock);
  }

  @Test
  @DisplayName("기존 회원이 없으면 새로 저장한다")
  void findOrCreateByOAuth_whenMemberAbsent_thenSavesNew() {
    // given
    givenNoMember();
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    Member result = memberService.findOrCreateByOAuth(kakaoUser("blur"));

    // then
    assertThat(result.getProvider()).isEqualTo(OAuthProvider.KAKAO);
    assertThat(result.getProviderId()).isEqualTo("kakao-1");
    assertThat(result.getName()).isEqualTo("blur");
    assertThat(result.getRole()).isEqualTo(MemberRole.USER);
    assertThat(result.getActivityStatus()).isEqualTo(ActivityStatus.ACTIVE);
    assertThat(result.getRegistrationStatus()).isEqualTo(RegistrationStatus.UNVERIFIED);
    verify(memberRepository).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("동시 가입으로 유니크 제약이 깨지면 MEMBER_OAUTH_CONFLICT 예외가 발생한다")
  void findOrCreateByOAuth_whenUniqueConflict_thenThrowsConflict() {
    // given
    givenNoMember();
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willThrow(new DataIntegrityViolationException(
            "duplicate key",
            new ConstraintViolationException(
                "duplicate", new SQLException("dup"), "uk_member_provider")));

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(kakaoUser("blur")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_OAUTH_CONFLICT.getCode());
  }

  @Test
  @DisplayName("provider 충돌이 아닌 무결성 위반은 충돌로 변환하지 않고 그대로 전파한다")
  void findOrCreateByOAuth_whenOtherIntegrityViolation_thenRethrows() {
    // given
    givenNoMember();
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willThrow(new DataIntegrityViolationException("Column 'foo' cannot be null"));

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(kakaoUser("blur")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("기존 회원이 있으면 쓰기 락으로 읽어 프로필을 갱신만 하고 새로 저장하지 않는다")
  void findOrCreateByOAuth_whenMemberExists_thenUpdatesProfile() {
    // given
    Member existing = member(1L);
    givenExistingMember(existing);

    // when
    Member result = memberService.findOrCreateByOAuth(kakaoUser("new-name"));

    // then
    assertThat(result).isSameAs(existing);
    assertThat(result.getName()).isEqualTo("new-name");
    verify(memberRepository, never()).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("정지(SUSPENDED) 회원이 로그인하면 MEMBER_SUSPENDED로 차단하고 재활성화하지 않는다")
  void findOrCreateByOAuth_whenMemberSuspended_thenThrowsAndKeepsSuspended() {
    // given
    Member suspended = member(1L);
    suspended.suspend();
    givenExistingMember(suspended);

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(kakaoUser("new-name")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_SUSPENDED.getCode());
    assertThat(suspended.isSuspended()).isTrue();
    verify(memberRepository, never()).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("탈퇴 후 30일 미만(30일-1초)이면 재활성화·가입 없이 MEMBER_409_REJOIN_RESTRICTED로 거부하고 가입 가능 시각을 알린다")
  void findOrCreateByOAuth_whenWithdrawnWithinRestriction_thenRejects() {
    // given
    Member withdrawn = withdrawnMember(1L, NOW.minusDays(30).plusSeconds(1));
    givenExistingMember(withdrawn);

    // when & then
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(kakaoUser("blur")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED")
        .hasMessageContaining("2026-10-25 12:00");
    assertThat(withdrawn.isWithdrawn()).isTrue();
    assertThat(withdrawn.getProviderId()).isEqualTo("kakao-1");
    verify(memberRepository, never()).saveAndFlush(any(Member.class));
  }

  @Test
  @DisplayName("탈퇴 후 정확히 30일이면 이전 행의 식별자를 해제하고 새 회원으로 가입시킨다")
  void findOrCreateByOAuth_whenWithdrawnExactlyThirtyDaysAgo_thenReleasesAndCreatesNew() {
    // given
    Member withdrawn = withdrawnMember(1L, NOW.minusDays(30));
    givenExistingMember(withdrawn);
    given(memberRepository.saveAndFlush(any(Member.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    Member result = memberService.findOrCreateByOAuth(kakaoUser("blur"));

    // then
    assertThat(result).isNotSameAs(withdrawn);
    assertThat(result.getProviderId()).isEqualTo("kakao-1");
    assertThat(result.getRegistrationStatus()).isEqualTo(RegistrationStatus.UNVERIFIED);
    assertThat(withdrawn.isIdentifiersReleased()).isTrue();
    assertThat(withdrawn.getProviderId()).isEqualTo("withdrawn:1");
    assertThat(withdrawn.getSchoolEmail()).isNull();
    // 이전 행의 유니크 값을 먼저 DB에 반영해야 새 행이 같은 식별자로 저장된다.
    verify(memberRepository).flush();
  }

  @Test
  @DisplayName("다른 활성 회원이 쓰는 학교 이메일이면 MEMBER_409_SCHOOL_EMAIL_DUPLICATED다")
  void validateSchoolEmailAvailable_whenActiveHolder_thenDuplicated() {
    // given
    given(memberRepository.findBySchoolEmail(SCHOOL_EMAIL)).willReturn(Optional.of(member(2L)));

    // when & then
    assertThatThrownBy(() -> memberService.validateSchoolEmailAvailable(1L, SCHOOL_EMAIL))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_SCHOOL_EMAIL_DUPLICATED");
  }

  @Test
  @DisplayName("탈퇴 후 30일 미만인 다른 회원의 학교 이메일은 다른 카카오 계정에서도 MEMBER_409_REJOIN_RESTRICTED다")
  void validateSchoolEmailAvailable_whenWithdrawnHolderWithinRestriction_thenRestricted() {
    // given
    given(memberRepository.findBySchoolEmail(SCHOOL_EMAIL))
        .willReturn(Optional.of(withdrawnMember(2L, NOW.minusDays(30).plusSeconds(1))));

    // when & then
    assertThatThrownBy(() -> memberService.validateSchoolEmailAvailable(1L, SCHOOL_EMAIL))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
  }

  @Test
  @DisplayName("탈퇴 후 정확히 30일 지난 회원의 학교 이메일은 사용할 수 있다")
  void validateSchoolEmailAvailable_whenWithdrawnHolderExpired_thenAllows() {
    // given
    given(memberRepository.findBySchoolEmail(SCHOOL_EMAIL))
        .willReturn(Optional.of(withdrawnMember(2L, NOW.minusDays(30))));

    // when & then
    memberService.validateSchoolEmailAvailable(1L, SCHOOL_EMAIL);
  }

  @Test
  @DisplayName("저장 직전 확인에서 30일 지난 탈퇴 보유자를 잠가 학교 이메일을 해제한다")
  void releaseSchoolEmailForReuse_whenExpiredHolder_thenReleases() {
    // given
    Member holder = withdrawnMember(2L, NOW.minusDays(30));
    given(memberRepository.findIdBySchoolEmail(SCHOOL_EMAIL)).willReturn(Optional.of(2L));
    given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(holder));

    // when
    memberService.releaseSchoolEmailForReuse(1L, SCHOOL_EMAIL);

    // then
    assertThat(holder.getSchoolEmail()).isNull();
    assertThat(holder.isIdentifiersReleased()).isTrue();
    verify(memberRepository).flush();
  }

  @Test
  @DisplayName("저장 직전 확인에서 보유자가 아직 30일 미만이면 해제하지 않고 거부한다")
  void releaseSchoolEmailForReuse_whenHolderWithinRestriction_thenRejects() {
    // given
    Member holder = withdrawnMember(2L, NOW.minusDays(29));
    given(memberRepository.findIdBySchoolEmail(SCHOOL_EMAIL)).willReturn(Optional.of(2L));
    given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(holder));

    // when & then
    assertThatThrownBy(() -> memberService.releaseSchoolEmailForReuse(1L, SCHOOL_EMAIL))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
    assertThat(holder.getSchoolEmail()).isEqualTo(SCHOOL_EMAIL);
  }

  @Test
  @DisplayName("정리 배치는 30일 지난 탈퇴 회원만 해제하고, 아직 제한 중이면 건너뛴다")
  void releaseExpiredWithdrawnMember_releasesOnlyExpired() {
    // given
    Member expired = withdrawnMember(2L, NOW.minusDays(31));
    Member restricted = withdrawnMember(3L, NOW.minusDays(1));
    given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(expired));
    given(memberRepository.findByIdForUpdate(3L)).willReturn(Optional.of(restricted));

    // when & then
    assertThat(memberService.releaseExpiredWithdrawnMember(2L)).isTrue();
    assertThat(memberService.releaseExpiredWithdrawnMember(3L)).isFalse();
    assertThat(expired.isIdentifiersReleased()).isTrue();
    assertThat(restricted.isIdentifiersReleased()).isFalse();
  }

  @Test
  @DisplayName("존재하지 않는 회원 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
  void findActiveMember_whenMemberNotFound_thenThrows() {
    // given
    given(memberRepository.findById(99L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> memberService.findActiveMember(99L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("비활성 회원 조회 시 MEMBER_INACTIVE 예외가 발생한다")
  void findActiveMember_whenMemberInactive_thenThrows() {
    // given
    Member inactive = member(1L);
    ReflectionTestUtils.setField(inactive, "activityStatus", ActivityStatus.WITHDRAWN);
    given(memberRepository.findById(1L)).willReturn(Optional.of(inactive));

    // when & then
    assertThatThrownBy(() -> memberService.findActiveMember(1L))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_INACTIVE.getCode());
  }

  private void givenNoMember() {
    given(memberRepository.findIdByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.empty());
  }

  private void givenExistingMember(Member member) {
    given(memberRepository.findIdByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1"))
        .willReturn(Optional.of(member.getId()));
    given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
  }

  private static OAuthUserInfo kakaoUser(String name) {
    return new OAuthUserInfo(OAuthProvider.KAKAO, "kakao-1", "test@example.com", name, "https://img");
  }

  private static Member member(Long id) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "kakao-1", "old-name", "old@example.com", "old-img");
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  /** {@code withdrawnAt}에 탈퇴했고 학교 이메일을 보유한 회원을 만든다. */
  private static Member withdrawnMember(Long id, LocalDateTime withdrawnAt) {
    Member member = member(id);
    member.verifySchoolEmail(SCHOOL_EMAIL);
    member.withdraw(withdrawnAt);
    return member;
  }
}
