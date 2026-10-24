package com.blursome.blursome.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.auth.service.AuthService;
import com.blursome.blursome.auth.token.RefreshTokenStore;
import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.ActivityStatus;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.dto.OAuthUserInfo;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.member.service.MemberOnboardingService;
import com.blursome.blursome.member.service.MemberService;
import com.blursome.blursome.member.service.MemberWithdrawalService;
import com.blursome.blursome.member.verification.EmailVerificationSender;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore;
import com.blursome.blursome.member.verification.SchoolEmailVerificationStore.VerificationEntry;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원 탈퇴·대화 종료·토큰 무효화·30일 재가입 제한(#107)을 실제 MySQL(Testcontainers)과 실제 보안 필터로 검증한다.
 *
 * <p>테스트 환경에 Redis·SMTP가 없으므로 Refresh Token·학교 이메일 인증 코드 저장소와 메일 발송만 목으로 바꾼다. 30일 경과는
 * {@code withdrawn_at}을 JDBC로 당겨 표현한다(정확한 경계 시각은 고정 {@code Clock}을 쓰는 단위 테스트가 검증한다).
 * 통합 테스트는 롤백되지 않으므로 회원 식별자는 매번 고유하게 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MemberWithdrawalIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final int RACE_TRIALS = 10;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtTokenProvider jwtTokenProvider;

  @Autowired
  private AuthService authService;

  @Autowired
  private MemberService memberService;

  @Autowired
  private MemberWithdrawalService withdrawalService;

  @Autowired
  private MemberOnboardingService onboardingService;

  @Autowired
  private ChatMessageService chatMessageService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired
  private ChatMessageRepository chatMessageRepository;

  @Autowired
  private DataSource dataSource;

  @MockitoBean
  private RefreshTokenStore refreshTokenStore;

  @MockitoBean
  private SchoolEmailVerificationStore verificationStore;

  @MockitoBean
  @SuppressWarnings("unused")
  private EmailVerificationSender emailVerificationSender;

  @Test
  @DisplayName("탈퇴하면 204·RT 쿠키 만료, 대화 종료(ACTIVE·BLOCKED→CLOSED, REPORTED 유지), Redis 정리, 기존 AT·RT 차단까지 이어진다")
  void withdraw_endToEnd() throws Exception {
    Member me = saveMember("me");
    Member activePartner = saveMember("active");
    Member blockedPartner = saveMember("blocked");
    Member reportedPartner = saveMember("reported");
    Long activeRoom = persistRoom(me, activePartner, ChatRoomStatus.ACTIVE);
    Long blockedRoom = persistRoom(me, blockedPartner, ChatRoomStatus.BLOCKED);
    Long reportedRoom = persistRoom(me, reportedPartner, ChatRoomStatus.REPORTED);
    String accessToken = jwtTokenProvider.issueAccessToken(me.getId(), MemberRole.USER);
    String refreshToken = jwtTokenProvider.issueRefreshToken(me.getId());

    mockMvc.perform(delete("/api/members/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isNoContent())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.allOf(
            Matchers.containsString("refreshToken="), Matchers.containsString("Max-Age=0"))));

    // 회원은 행을 지우지 않고 WITHDRAWN + withdrawnAt으로 남는다.
    Member withdrawn = memberRepository.findById(me.getId()).orElseThrow();
    assertThat(withdrawn.getActivityStatus()).isEqualTo(ActivityStatus.WITHDRAWN);
    assertThat(withdrawn.getWithdrawnAt()).isNotNull();

    // 대화 종료: 탈퇴 회원 멤버십은 모두 leftAt, ACTIVE·BLOCKED는 CLOSED, REPORTED는 신고 검토 상태 유지.
    assertThat(roomStatus(activeRoom)).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(roomStatus(blockedRoom)).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(roomStatus(reportedRoom)).isEqualTo(ChatRoomStatus.REPORTED);
    assertThat(List.of(activeRoom, blockedRoom, reportedRoom))
        .allSatisfy(roomId -> assertThat(
            chatRoomMemberRepository.findMembership(roomId, me.getId()).orElseThrow().hasLeft())
            .isTrue());

    // 상대는 종료된 방에 메시지를 보낼 수 없다.
    assertThatThrownBy(() -> chatMessageService.send(activeRoom, activePartner.getId(),
        new ChatMessageSendRequest(ChatMessageType.TEXT, "안녕")))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());

    // 커밋 이후 Redis의 RT·학교 이메일 인증 코드를 지운다.
    verify(refreshTokenStore, timeout(2000)).delete(me.getId());
    verify(verificationStore, timeout(2000)).delete(me.getId());

    // 남은 AT로는 보호 API를 쓸 수 없고, 다시 탈퇴를 요청해도 같은 403이다.
    mockMvc.perform(get("/api/members/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MEMBER_403_INACTIVE"));
    mockMvc.perform(delete("/api/members/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MEMBER_403_INACTIVE"));

    // Redis 삭제가 실패해 RT가 남아 있어도 refresh는 회원 상태 검사로 막힌다.
    given(refreshTokenStore.find(me.getId())).willReturn(Optional.of(refreshToken));
    assertThatThrownBy(() -> authService.refresh(refreshToken))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_INACTIVE.getCode());
  }

  @Test
  @DisplayName("탈퇴와 상대의 메시지 송신이 동시에 일어나도 탈퇴 커밋 후에는 메시지가 저장되지 않는다")
  void withdrawAndSendConcurrently_thenNoMessageAfterClose() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < RACE_TRIALS; i++) {
        Member me = saveMember("race-me");
        Member partner = saveMember("race-partner");
        Long roomId = persistRoom(me, partner, ChatRoomStatus.ACTIVE);

        List<Future<Object>> results = runConcurrently(pool,
            () -> {
              withdrawalService.withdraw(me.getId());
              return "withdrawn";
            },
            () -> chatMessageService.send(roomId, partner.getId(),
                new ChatMessageSendRequest(ChatMessageType.TEXT, "동시-전송")));

        results.get(0).get(20, TimeUnit.SECONDS);
        assertThat(roomStatus(roomId)).isEqualTo(ChatRoomStatus.CLOSED);
        long saved = chatMessageRepository.findHistory(roomId, null, PageRequest.ofSize(10)).size();
        Throwable sendError = failureOf(results.get(1));
        if (sendError == null) {
          // 송신이 방 락을 먼저 잡아 ACTIVE에서 저장한 경우 → 정확히 1건.
          assertThat(saved).isEqualTo(1L);
        } else {
          assertThat(sendError).isInstanceOf(BaseException.class)
              .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
          assertThat(saved).isZero();
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("같은 카카오 계정은 탈퇴 후 30일 미만이면 토큰 없이 거부되고, 30일이 지나면 새 회원으로 가입된다")
  void kakaoRejoin_isRestrictedForThirtyDays() {
    String providerId = "kakao-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member original = memberService.findOrCreateByOAuth(kakaoUser(providerId));
    withdrawalService.withdraw(original.getId());

    setWithdrawnAt(original.getId(), LocalDateTime.now().minusDays(30).plusMinutes(1));
    assertThatThrownBy(() -> memberService.findOrCreateByOAuth(kakaoUser(providerId)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
    assertThat(memberRepository.findById(original.getId()).orElseThrow().getProviderId())
        .isEqualTo(providerId);

    setWithdrawnAt(original.getId(), LocalDateTime.now().minusDays(30).minusSeconds(1));
    Member rejoined = memberService.findOrCreateByOAuth(kakaoUser(providerId));

    assertThat(rejoined.getId()).isNotEqualTo(original.getId());
    assertThat(rejoined.isActive()).isTrue();
    assertThat(rejoined.isSchoolVerified()).isFalse();
    Member released = memberRepository.findById(original.getId()).orElseThrow();
    assertThat(released.isWithdrawn()).isTrue();
    assertThat(released.isIdentifiersReleased()).isTrue();
    assertThat(released.getProviderId()).isEqualTo("withdrawn:" + original.getId());
  }

  @Test
  @DisplayName("탈퇴 회원의 학교 이메일은 다른 카카오 계정에서도 30일 미만이면 거부되고, 30일이 지나면 인증할 수 있다")
  void schoolEmailReuse_fromOtherKakaoAccount_isRestrictedForThirtyDays() {
    String email = "rejoin" + SEQ.incrementAndGet() + "@gs.anyang.ac.kr";
    Member original = saveMember("email-owner");
    verifyEmail(original.getId(), email);
    withdrawalService.withdraw(original.getId());
    Member other = saveMember("email-reuser");

    setWithdrawnAt(original.getId(), LocalDateTime.now().minusDays(30).plusMinutes(1));
    assertThatThrownBy(() -> onboardingService.sendSchoolEmailVerificationCode(other.getId(), email))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
    // 대소문자만 바꿔도 같은 이메일로 본다.
    assertThatThrownBy(() -> onboardingService.sendSchoolEmailVerificationCode(
        other.getId(), email.toUpperCase()))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");

    setWithdrawnAt(original.getId(), LocalDateTime.now().minusDays(30).minusSeconds(1));
    onboardingService.sendSchoolEmailVerificationCode(other.getId(), email);
    verifyEmail(other.getId(), email);

    assertThat(memberRepository.findById(other.getId()).orElseThrow().getSchoolEmail())
        .isEqualTo(email);
    assertThat(memberRepository.findById(original.getId()).orElseThrow().getSchoolEmail()).isNull();
  }

  @Test
  @DisplayName("30일 지난 탈퇴 회원의 학교 이메일을 두 회원이 동시에 인증해도 한 명만 성공하고 나머지는 409다(500 없음)")
  void concurrentSchoolEmailVerification_onlyOneSucceeds() throws Exception {
    String email = "race" + SEQ.incrementAndGet() + "@gs.anyang.ac.kr";
    Member original = saveMember("race-owner");
    verifyEmail(original.getId(), email);
    withdrawalService.withdraw(original.getId());
    setWithdrawnAt(original.getId(), LocalDateTime.now().minusDays(31));
    Member first = saveMember("race-first");
    Member second = saveMember("race-second");
    givenVerificationCode(first.getId(), email);
    givenVerificationCode(second.getId(), email);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> results = runConcurrently(pool,
          () -> {
            onboardingService.verifySchoolEmail(first.getId(), email, "123456");
            return "ok";
          },
          () -> {
            onboardingService.verifySchoolEmail(second.getId(), email, "123456");
            return "ok";
          });
      List<Throwable> failures = results.stream().map(this::failureOf)
          .filter(java.util.Objects::nonNull).toList();

      assertThat(failures).hasSize(1);
      assertThat(failures.get(0)).isInstanceOf(BaseException.class)
          .hasFieldOrPropertyWithValue("code", "MEMBER_409_SCHOOL_EMAIL_DUPLICATED");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("탈퇴와 같은 카카오 계정 로그인이 동시에 일어나도 회원은 탈퇴 상태로 남고 로그인은 성공 또는 재가입 제한뿐이다")
  void withdrawAndLoginConcurrently_thenStaysWithdrawn() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < RACE_TRIALS; i++) {
        String providerId = "kakao-race-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        Member member = memberService.findOrCreateByOAuth(kakaoUser(providerId));

        List<Future<Object>> results = runConcurrently(pool,
            () -> {
              withdrawalService.withdraw(member.getId());
              return "withdrawn";
            },
            () -> memberService.findOrCreateByOAuth(kakaoUser(providerId)));

        results.get(0).get(20, TimeUnit.SECONDS);
        Throwable loginError = failureOf(results.get(1));
        if (loginError != null) {
          assertThat(loginError).isInstanceOf(BaseException.class)
              .hasFieldOrPropertyWithValue("code", "MEMBER_409_REJOIN_RESTRICTED");
        }
        // 로그인이 먼저였든 나중이었든, 로그인의 활동 기록이 탈퇴를 되돌리지 않는다.
        assertThat(memberRepository.findById(member.getId()).orElseThrow().isWithdrawn()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------- helpers ----------

  /** 두 작업을 동시에 출발시키고 둘 다 끝날 때까지 기다린다. */
  private List<Future<Object>> runConcurrently(ExecutorService pool, Callable<Object> first,
      Callable<Object> second) throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    Future<Object> a = pool.submit(() -> {
      start.await();
      return first.call();
    });
    Future<Object> b = pool.submit(() -> {
      start.await();
      return second.call();
    });
    start.countDown();
    for (Future<Object> future : List.of(a, b)) {
      try {
        future.get(20, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // 결과는 호출 측이 failureOf로 확인한다. 시간 초과(교착)는 아래 isDone 검증에서 드러난다.
      }
    }
    assertThat(a.isDone() && b.isDone()).as("동시 작업이 교착 없이 끝나야 한다").isTrue();
    return List.of(a, b);
  }

  /** 끝난 작업의 예외를 꺼낸다. 성공했으면 {@code null}. */
  private Throwable failureOf(Future<Object> future) {
    try {
      future.get();
      return null;
    } catch (java.util.concurrent.ExecutionException e) {
      return e.getCause();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void verifyEmail(Long memberId, String email) {
    givenVerificationCode(memberId, email);
    onboardingService.verifySchoolEmail(memberId, email, "123456");
  }

  private void givenVerificationCode(Long memberId, String email) {
    given(verificationStore.find(memberId))
        .willReturn(Optional.of(new VerificationEntry(email, "123456")));
  }

  private void setWithdrawnAt(Long memberId, LocalDateTime withdrawnAt) {
    new JdbcTemplate(dataSource).update("UPDATE member SET withdrawn_at = ? WHERE id = ?",
        Timestamp.valueOf(withdrawnAt), memberId);
  }

  private ChatRoomStatus roomStatus(Long roomId) {
    return chatRoomRepository.findById(roomId).orElseThrow().getRoomStatus();
  }

  private Long persistRoom(Member a, Member b, ChatRoomStatus status) {
    ChatRoom room = ChatRoom.createOnMatched(a.getId(), b.getId());
    if (status == ChatRoomStatus.BLOCKED) {
      room.markBlocked();
    } else if (status == ChatRoomStatus.REPORTED) {
      room.markReported();
    }
    ChatRoom saved = chatRoomRepository.save(room);
    chatRoomMemberRepository.save(ChatRoomMember.join(saved, a));
    chatRoomMemberRepository.save(ChatRoomMember.join(saved, b));
    return saved.getId();
  }

  private Member saveMember(String tag) {
    String unique = tag + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    return memberRepository.save(Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null));
  }

  private static OAuthUserInfo kakaoUser(String providerId) {
    return new OAuthUserInfo(OAuthProvider.KAKAO, providerId, null, "blur", null);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
