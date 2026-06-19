package com.blursome.blursome.global.health;

import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "배포 상태 확인용 헬스체크 API")
@RestController
@RequestMapping("/api/ping")
public class PingController {

  @Operation(summary = "배포 헬스체크",
      description = "애플리케이션이 정상 기동했는지 확인한다. 인증 없이 호출 가능하며 항상 \"pong\"을 반환한다.")
  @GetMapping
  public ResponseEntity<DataResponse<String>> ping() {
    return ResponseEntity.ok(DataResponse.ok("pong"));
  }
}
