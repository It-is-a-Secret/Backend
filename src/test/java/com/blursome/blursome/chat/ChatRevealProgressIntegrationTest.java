package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사진 공개 단계가 <b>유효 메시지 누적 수</b>로 오르는지 실제 MySQL(Testcontainers)·실제 서비스로 종단 검증한다(이슈 #79).
 *
 * <p>발신자별 2초 디바운스를 실제로 기다리면 느리고 불안정하므로 {@link Clock}을 목으로 대체해 매 호출 시각을
 * 충분히 띄운다(시간만 통제, 카운트·단계 전이·DB 영속은 모두 실제 경로를 탄다). 송신 경로가 방 행을 비관적
 * 락으로 잡아 방 단위로 직렬화하므로 카운터 증분·단계 전이가 안전하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatRevealProgressIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final Instant BASE = Instant.parse("2026-06-25T00:00:00Z");

  @Autowired
  private ChatMessageService chatMessageService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @MockitoBean
  private Clock clock;

  @BeforeEach
  void setUp() {
    // now() 호출마다 5초씩 전진시켜 발신자별 2초 디바운스를 항상 통과시킨다.
    AtomicInteger tick = new AtomicInteger();
    BDDMockito.given(clock.getZone()).willReturn(ZoneOffset.UTC);
    BDDMockito.given(clock.instant())
        .willAnswer(invocation -> BASE.plusSeconds(5L * tick.getAndIncrement()));
  }

  @Test
  @DisplayName("양쪽이 교대로 유효 메시지를 10개씩 누적하면(min=10) 방 단계가 STEP_1로 오른다")
  void alternatingValidMessages_advanceToStep1() {
    Fixture f = persistRoom();

    // 9쌍(=18개) 누적: A=9, B=9 → min(9,9)=9 → 아직 MATCHED.
    exchange(f, 9);
    assertThat(reloadProgress(f.roomId())).isEqualTo(ChatRoomProgressStatus.MATCHED);

    // 1쌍 더(=20개): A=10, B=10 → min=10 → STEP_1.
    exchange(f, 1);
    assertThat(reloadProgress(f.roomId())).isEqualTo(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1);
    assertThat(reloadCount(f.aMembershipId())).isEqualTo(10);
    assertThat(reloadCount(f.bMembershipId())).isEqualTo(10);
  }

  @Test
  @DisplayName("같은 발신자만 연속으로 보내면 첫 1건만 카운트되고(교대 발화) 단계는 오르지 않는다")
  void sameSenderOnly_countsOnceAndStaysMatched() {
    Fixture f = persistRoom();

    for (int i = 0; i < 10; i++) {
      chatMessageService.send(f.roomId(), f.aId(), text());
    }

    assertThat(reloadCount(f.aMembershipId())).isEqualTo(1);
    assertThat(reloadCount(f.bMembershipId())).isZero();
    assertThat(reloadProgress(f.roomId())).isEqualTo(ChatRoomProgressStatus.MATCHED);
  }

  // ---------- helpers ----------

  /** A→B 순으로 유효 메시지를 {@code pairs}쌍 주고받는다(교대 발화). */
  private void exchange(Fixture f, int pairs) {
    for (int i = 0; i < pairs; i++) {
      chatMessageService.send(f.roomId(), f.aId(), text());
      chatMessageService.send(f.roomId(), f.bId(), text());
    }
  }

  private ChatMessageSendRequest text() {
    return new ChatMessageSendRequest(ChatMessageType.TEXT, "충분히 긴 유효 메시지");
  }

  private ChatRoomProgressStatus reloadProgress(Long roomId) {
    return chatRoomRepository.findById(roomId).orElseThrow().getProgressStatus();
  }

  private int reloadCount(Long membershipId) {
    return chatRoomMemberRepository.findById(membershipId).orElseThrow().getValidMessageCount();
  }

  private Fixture persistRoom() {
    Member a = saveMember();
    Member b = saveMember();
    ChatRoom room = chatRoomRepository.save(ChatRoom.createOnMatched(a.getId(), b.getId()));
    ChatRoomMember aMember = chatRoomMemberRepository.save(ChatRoomMember.join(room, a));
    ChatRoomMember bMember = chatRoomMemberRepository.save(ChatRoomMember.join(room, b));
    return new Fixture(room.getId(), a.getId(), b.getId(), aMember.getId(), bMember.getId());
  }

  private Member saveMember() {
    String unique = "m-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, "name", unique + "@example.com", null);
    return memberRepository.save(member);
  }

  private record Fixture(Long roomId, Long aId, Long bId, Long aMembershipId, Long bMembershipId) {
  }
}
