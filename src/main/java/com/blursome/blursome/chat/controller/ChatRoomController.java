package com.blursome.blursome.chat.controller;

import com.blursome.blursome.chat.dto.response.ChatMessageResponse;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.response.DataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chat", description = "채팅방 조회 API")
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private static final int DEFAULT_PAGE_SIZE = 30;

  private final ChatRoomService chatRoomService;

  @Operation(summary = "내 채팅방 목록 조회", description = "참여 중인(나가지 않은) 방을 안읽음 수와 함께 최신순으로 반환한다.")
  @GetMapping
  public ResponseEntity<DataResponse<List<ChatRoomSummaryResponse>>> getMyRooms(
      @AuthenticationPrincipal Long memberId
  ) {
    return ResponseEntity.ok(DataResponse.ok(chatRoomService.getMyRooms(memberId)));
  }

  @Operation(summary = "채팅방 단건 조회", description = "참여 중인 방의 요약 정보를 반환한다. 참여자가 아니면 403.")
  @GetMapping("/{roomId}")
  public ResponseEntity<DataResponse<ChatRoomSummaryResponse>> getRoom(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long roomId
  ) {
    return ResponseEntity.ok(DataResponse.ok(chatRoomService.getRoom(roomId, memberId)));
  }

  @Operation(summary = "메시지 이력 조회", description = "id 커서 페이지네이션으로 과거 메시지를 최신순 조회한다.")
  @GetMapping("/{roomId}/messages")
  public ResponseEntity<DataResponse<List<ChatMessageResponse>>> getMessages(
      @AuthenticationPrincipal Long memberId,
      @PathVariable Long roomId,
      @Parameter(description = "이 id보다 과거의 메시지를 조회(미지정 시 최신부터)")
      @RequestParam(required = false) Long cursor,
      @Parameter(description = "페이지 크기(1~100으로 보정됨)")
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size
  ) {
    return ResponseEntity.ok(
        DataResponse.ok(chatRoomService.getMessageHistory(roomId, memberId, cursor, size)));
  }
}
