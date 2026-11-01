package com.blursome.blursome.global.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.blursome.blursome.chat.domain.ChatMessageType;
import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.context.ActiveProfiles;

/**
 * REST 응답과 STOMP 메시지가 같은 기준(한국 시간 오프셋)으로 시각을 내려보내는지 검증한다(#113).
 *
 * <p>엔티티 시각은 서버 기본 시간대의 {@link LocalDateTime}이므로, 테스트도 고정된 순간을 서버 기본 시간대로 바꿔
 * 입력한다. 테스트를 돌리는 장비의 시간대(KST·UTC)와 무관하게 같은 결과가 나와야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SeoulTimeSerializationIntegrationTest {

  private static final String EXPECTED = "\"createdAt\":\"2026-10-28T13:22:14.828729+09:00\"";

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("brokerMessageConverter")
  private CompositeMessageConverter brokerMessageConverter;

  private static ChatMessageResponse message() {
    LocalDateTime serverLocal = LocalDateTime.ofInstant(
        Instant.parse("2026-10-28T04:22:14.828729Z"), ZoneId.systemDefault());
    return new ChatMessageResponse(1L, 2L, 3L, ChatMessageType.TEXT, "hi", serverLocal);
  }

  @Test
  @DisplayName("REST(ObjectMapper): LocalDateTime을 +09:00 오프셋이 붙은 한국 시간으로 직렬화한다")
  void rest_serializesLocalDateTimeAsSeoulOffset() throws Exception {
    assertThat(objectMapper.writeValueAsString(message())).contains(EXPECTED);
  }

  @Test
  @DisplayName("STOMP(브로커 메시지 컨버터): REST와 같은 한국 시간 오프셋 형식으로 직렬화한다")
  void stomp_serializesLocalDateTimeAsSeoulOffset() {
    Message<?> converted = brokerMessageConverter.toMessage(message(), new MessageHeaders(null));

    assertThat(converted).isNotNull();
    assertThat(new String((byte[]) converted.getPayload(), StandardCharsets.UTF_8)).contains(EXPECTED);
  }
}
