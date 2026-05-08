package com.blursome.blursome.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataResponse<T> extends BaseResponse {

  private final T data;

  public DataResponse(HttpStatus status, T data) {
    super(status);
    this.data = data;
  }

  public static <T> DataResponse<T> from(T data) {
    return new DataResponse<>(HttpStatus.OK, data);
  }

  /**
   * data가 null인 경우 아래 같이 사용합니다
   * ResponseEntity.noContent().build()
   **/
}