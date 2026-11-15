package com.blursome.blursome.notification.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatPushPayloadTest {

  @Test
  @DisplayName("title은 발신자 닉네임, url은 /chat/{roomId}, type은 NEW_MESSAGE다")
  void newMessage_whenText_thenNicknameTitleAndChatUrl() {
    // when
    ChatPushPayload payload = ChatPushPayload.newMessage(12L, 345L, "블러곰",
        ChatMessageType.TEXT, "안녕하세요");

    // then
    assertThat(payload.type()).isEqualTo("NEW_MESSAGE");
    assertThat(payload.roomId()).isEqualTo(12L);
    assertThat(payload.messageId()).isEqualTo(345L);
    assertThat(payload.title()).isEqualTo("블러곰");
    assertThat(payload.body()).isEqualTo("안녕하세요");
    assertThat(payload.url()).isEqualTo("/chat/12");
  }

  @Test
  @DisplayName("본문이 80자를 넘으면 앞 80자만 남기고 말줄임표를 붙인다")
  void newMessage_whenLongerThan80_thenTruncated() {
    // given
    String content = "가".repeat(100);

    // when
    String body = ChatPushPayload.newMessage(1L, 1L, "a", ChatMessageType.TEXT, content).body();

    // then
    assertThat(body).isEqualTo("가".repeat(80) + "…");
  }

  @Test
  @DisplayName("정확히 80자면 자르지 않고, 이모지(서로게이트 쌍)는 반으로 잘리지 않는다")
  void newMessage_whenExactly80OrEmoji_thenKeepsCodePoints() {
    assertThat(ChatPushPayload.newMessage(1L, 1L, "a", ChatMessageType.TEXT, "a".repeat(80)).body())
        .isEqualTo("a".repeat(80));

    String body = ChatPushPayload.newMessage(1L, 1L, "a", ChatMessageType.TEXT, "😀".repeat(81)).body();
    assertThat(body).isEqualTo("😀".repeat(80) + "…");
  }

  @Test
  @DisplayName("줄바꿈·연속 공백은 한 칸으로 줄인다")
  void newMessage_whenMultiline_thenWhitespaceCollapsed() {
    String body = ChatPushPayload.newMessage(1L, 1L, "a", ChatMessageType.TEXT, "  첫 줄\n\n둘째   줄 ").body();

    assertThat(body).isEqualTo("첫 줄 둘째 줄");
  }

  @Test
  @DisplayName("이미지 메시지는 고정 문구, 닉네임이 없으면 BlurSome을 제목으로 쓴다")
  void newMessage_whenImageAndNoNickname_thenFallbacks() {
    ChatPushPayload payload = ChatPushPayload.newMessage(1L, 1L, null, ChatMessageType.IMAGE,
        "feeds/1/original.jpg");

    assertThat(payload.body()).isEqualTo("사진을 보냈어요");
    assertThat(payload.title()).isEqualTo("BlurSome");
  }
}
