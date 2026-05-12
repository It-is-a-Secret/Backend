package com.blursome.blursome.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataResponse<T> extends BaseResponse {

  private final T data;

  private DataResponse(T data) {
    this.data = data;
  }

  // Response Header를 통한 상태 코드 전달
  public static <T> DataResponse<T> ok(T data) {
    return new DataResponse<>(data);
  }

  /**
   * data가 null인 경우 아래 같이 사용합니다
   * ResponseEntity.noContent().build()
   **/
}
