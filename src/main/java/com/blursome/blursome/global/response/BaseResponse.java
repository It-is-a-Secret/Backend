package com.blursome.blursome.global.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.Getter;

@Getter
public abstract class BaseResponse {

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
  // RFC3339 패턴
  private final OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));

}
