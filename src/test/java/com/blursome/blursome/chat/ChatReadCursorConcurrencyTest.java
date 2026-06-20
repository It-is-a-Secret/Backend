package com.blursome.blursome.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessage;
import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.repository.ChatMessageRepository;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatMessageService;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 읽음 커서 단조 증가(역행 방지)를 실제 MySQL(Testcontainers)로 검증한다(설계 §7-4, P1 동시성 리뷰).
 *
 * <p>엔티티 read-modify-write 대신 조건부 UPDATE({@code advanceLastReadMessage})를 쓰므로, 순서가 역전된 요청이나
 * 동시 요청에서도 커서가 더 작은 id로 되돌아가지 않아야 한다. 단위 테스트(Mock)로는 DB 직렬화를 검증할 수 없어
 * 통합 테스트로 다룬다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatReadCursorConcurrencyTest {

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
  @DisplayName("더 과거 커서로의 읽음 요청이 나중에 와도 커서는 역행하지 않는다")
  void markAsRead_whenOlderCursorAfterNewer_thenDoesNotRegress() {
    Fixture fixture = persistRoomWithMessages(30);
    Long newerId = fixture.messageIds().get(29);
    Long olderId = fixture.messageIds().get(5);

    chatMessageService.markAsRead(fixture.roomId(), fixture.readerId(), newerId);
    Long effective = chatMessageService.markAsRead(fixture.roomId(), fixture.readerId(), olderId);

    assertThat(effective).isEqualTo(newerId);
    assertThat(chatRoomMemberRepository.findLastReadMessageId(fixture.roomId(), fixture.readerId()))
        .contains(newerId);
  }

  @Test
  @DisplayName("여러 스레드가 동시에 서로 다른 커서로 읽어도 최종 커서는 최댓값으로 수렴한다")
  void markAsRead_whenConcurrent_thenConvergesToMaxCursor() throws InterruptedException {
    int threads = 16;
    Fixture fixture = persistRoomWithMessages(threads);
    Long maxId = fixture.messageIds().get(threads - 1);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    AtomicInteger index = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
      pool.submit(() -> {
        // 모든 메시지 id(최댓값 포함)가 최소 한 번씩 요청되도록 라운드로빈으로 분배한다.
        Long target =
            fixture.messageIds().get(index.getAndIncrement() % fixture.messageIds().size());
        ready.countDown();
        try {
          start.await();
          chatMessageService.markAsRead(fixture.roomId(), fixture.readerId(), target);
        } catch (Throwable t) {
          failures.add(t);
        } finally {
          done.countDown();
        }
      });
    }

    ready.await(5, TimeUnit.SECONDS);
    start.countDown();          // 동시에 출발시켜 경합을 최대화
    done.await(20, TimeUnit.SECONDS);
    pool.shutdownNow();

    assertThat(failures).isEmpty();
    assertThat(chatRoomMemberRepository.findLastReadMessageId(fixture.roomId(), fixture.readerId()))
        .contains(maxId);
  }

  /** 방·두 참여자·발신자가 보낸 메시지 {@code count}개를 영속화하고, 읽는 쪽(reader) 기준 식별자들을 돌려준다. */
  private Fixture persistRoomWithMessages(int count) {
    Member sender = memberRepository.save(oauthMember("sender"));
    Member reader = memberRepository.save(oauthMember("reader"));
    ChatRoom room = chatRoomRepository.save(
        ChatRoom.createOnMatched(sender.getId(), reader.getId()));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, sender));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, reader));

    List<Long> messageIds = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ChatMessage message = chatMessageRepository.save(
          ChatMessage.createTextMessage(room, sender, "msg-" + i));
      messageIds.add(message.getId());
    }
    return new Fixture(room.getId(), reader.getId(), messageIds);
  }

  private Member oauthMember(String tag) {
    String unique = tag + "-" + System.nanoTime();
    return Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, tag, unique + "@example.com", null);
  }

  private record Fixture(Long roomId, Long readerId, List<Long> messageIds) {
  }
}
