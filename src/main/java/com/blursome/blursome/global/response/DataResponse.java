package com.blursome.blursome.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataResponse<T> extends BaseResponse {

  private final T data;

  private DataResponse(HttpStatus status, T data) {
    super(status);
    this.data = data;
  }

  // 가장 흔한 케이스 (200 OK)
  public static <T> DataResponse<T> ok(T data) {
    return new DataResponse<>(HttpStatus.OK, data);
  }

  // 생성이 성공한 케이스 (201 Created)
  public static <T> DataResponse<T> created(T data) {
    return new DataResponse<>(HttpStatus.CREATED, data);
  }

  // 만약 유동적인 상태값이 필요하다면 (최소한의 노출)
  public static <T> DataResponse<T> of(T data, HttpStatus status) {
    return new DataResponse<>(status, data);
  }
  /**
   * data가 null인 경우 아래 같이 사용합니다
   * ResponseEntity.noContent().build()
   **/
}