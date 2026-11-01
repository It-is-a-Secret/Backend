package com.blursome.blursome.global.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.JsonComponent;

/**
 * 응답의 모든 {@link LocalDateTime}을 한국 시간 오프셋 문자열로 직렬화한다(#113).
 *
 * <p>예: 서버가 UTC인 경우 {@code 2026-10-28T04:22:14.828729} → {@code 2026-10-28T13:22:14.828729+09:00}.
 * 시간대 없는 값을 내려보내면 클라이언트가 자기 시간대로 해석해 시각이 어긋나므로, 항상 오프셋을 붙인다.
 *
 * <p>{@link JsonComponent}로 Spring Boot의 {@code ObjectMapper}에 등록되므로 REST 응답과 STOMP 메시지
 * ({@code WebSocketMessagingAutoConfiguration}이 같은 {@code ObjectMapper}로 메시지 컨버터를 구성)에 같은 기준이
 * 적용된다. 요청 본문에는 {@code LocalDateTime} 필드가 없어 역직렬화는 바꾸지 않는다.
 */
@JsonComponent
public class SeoulLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

  @Override
  public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
      throws IOException {
    generator.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(SeoulTime.toOffset(value)));
  }

  @Override
  public Class<LocalDateTime> handledType() {
    return LocalDateTime.class;
  }
}
