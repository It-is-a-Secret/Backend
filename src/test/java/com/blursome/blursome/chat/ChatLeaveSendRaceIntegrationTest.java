package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.dto.request.ChatMessageSendRequest;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 퇴장과 메시지 송신의 동시 실행 경쟁 조건을 실제 MySQL(Testcontainers)로 검증한다(설계 §7-6 동시성).
 *
 * <p>쓰기 경로({@code getWritableMembership})와 퇴장({@code leaveRoom})이 같은 {@code chat_room} 행을 비관적
 * 쓰기 락({@code findByIdForUpdate})으로 먼저 잡아 직렬화하므로, "송신이 ACTIVE를 확인한 직후 상대가 나가" 메시지가
 * 종료된 방에 새는 일이 없어야 한다. 락이 두 트랜잭션을 직렬화하면 최종 상태는 항상 일관된다 —
 * <b>메시지가 저장됐다면 송신이 성공한 것이고, 송신이 막혔다면(ROOM_CLOSED) 메시지가 없어야</b> 한다.
 * 단위 테스트(Mock)로는 DB 락의 직렬화를 검증할 수 없어 통합 테스트로 다룬다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatLeaveSendRaceIntegrationTest {

  private static final int TRIALS = 20;

  @Autowired
  private ChatRoomService chatRoomService;

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

  @Test
  @DisplayName("퇴장과 송신을 동시에 실행해도 최종 상태가 일관된다(종료된 방에 메시지가 새지 않음)")
  void leaveAndSendConcurrently_thenStateIsConsistent() throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < TRIALS; i++) {
        runOneTrial(pool);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** 방마다 송신(B)과 퇴장(A)을 동시에 출발시키고, 끝난 뒤 최종 상태의 일관성을 검증한다. */
  private void runOneTrial(ExecutorService pool) throws InterruptedException {
    Fixture f = persistRoom();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicBoolean sendSucceeded = new AtomicBoolean(false);
    AtomicReference<BaseException> sendError = new AtomicReference<>();

    // 스레드 1: 남은 사용자(B)가 메시지를 보내려 시도
    pool.submit(() -> {
      ready.countDown();
      awaitQuietly(start);
      try {
        chatMessageService.send(f.roomId(), f.bId(), new ChatMessageSendRequest(
            ChatMessageType.TEXT, "동시-전송"));
        sendSucceeded.set(true);
      } catch (BaseException e) {
        sendError.set(e);
      } finally {
        done.countDown();
      }
    });
    // 스레드 2: 상대(A)가 동시에 퇴장
    pool.submit(() -> {
      ready.countDown();
      awaitQuietly(start);
      try {
        chatRoomService.leaveRoom(f.roomId(), f.aId());
      } finally {
        done.countDown();
      }
    });

    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    assertThat(done.await(20, TimeUnit.SECONDS)).as("동시 작업이 교착 없이 끝나야 한다").isTrue();

    // 방은 항상 종료된다(A가 나갔으므로).
    assertThat(chatRoomRepository.findById(f.roomId()).orElseThrow().isActive()).isFalse();

    long savedMessages = chatMessageRepository
        .findHistory(f.roomId(), null, PageRequest.ofSize(10)).size();

    // 핵심 불변식: 락 직렬화로 송신과 종료가 찢어지지 않는다.
    if (sendSucceeded.get()) {
      // 송신이 성공했다면 그건 락을 먼저 잡아 ACTIVE 상태에서 저장한 것 → 메시지 1건 존재.
      assertThat(savedMessages).as("성공한 송신은 정확히 1건 저장").isEqualTo(1L);
    } else {
      // 송신이 막혔다면 사유는 ROOM_CLOSED여야 하고, 종료된 방에 메시지가 새지 않아야 한다.
      assertThat(sendError.get()).isNotNull();
      assertThat(sendError.get().getCode()).isEqualTo(ChatErrorCode.ROOM_CLOSED.getCode());
      assertThat(savedMessages).as("막힌 송신은 메시지를 남기지 않음").isZero();
    }
  }

  private void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private Fixture persistRoom() {
    Member a = saveMember("a");
    Member b = saveMember("b");
    ChatRoom room = chatRoomRepository.save(ChatRoom.createOnMatched(a.getId(), b.getId()));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, a));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, b));
    return new Fixture(room.getId(), a.getId(), b.getId());
  }

  private Member saveMember(String tag) {
    String unique = tag + "-" + System.nanoTime();
    return memberRepository.save(Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null));
  }

  private record Fixture(Long roomId, Long aId, Long bId) {
  }
}
