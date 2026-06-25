package com.blursome.blursome.report.controller;

import com.blursome.blursome.report.dto.request.ReportRequest;
import com.blursome.blursome.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Report", description = "신고 API")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

  private final ReportService reportService;

  @Operation(summary = "신고 접수",
      description = "대상 회원을 신고한다. chatRoomId가 있으면 채팅방 신고, 없으면 프로필 신고. 자기 신고는 400, "
          + "대상/방 없음은 404, 동일 대상·방 중복 신고는 409. 채팅방 신고가 고유 신고자 3명에 도달하면 방이 "
          + "동결(REPORTED)된다.")
  @PostMapping
  public ResponseEntity<Void> report(
      @AuthenticationPrincipal Long memberId,
      @Valid @RequestBody ReportRequest request
  ) {
    reportService.report(memberId, request);
    return ResponseEntity.noContent().build();
  }
}
