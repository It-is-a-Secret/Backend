package com.blursome.blursome.global.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class BaseResponse {

  private final String status;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
  // RFC3339 패턴
  private final OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));

  protected BaseResponse(HttpStatus status) {
    this.status = status.getReasonPhrase();
  }


}