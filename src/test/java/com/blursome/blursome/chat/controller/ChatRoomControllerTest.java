package com.blursome.blursome.chat.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.chat.domain.ChatRoomProgressStatus;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.chat.dto.response.ChatRoomSummaryResponse;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.security.JwtAuthentication;
import com.blursome.blursome.global.security.JwtAuthenticationEntryPoint;
import com.blursome.blursome.global.security.JwtAuthenticationFilter;
import com.blursome.blursome.global.security.JwtTokenProvider;
import com.blursome.blursome.member.domain.MemberRole;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = ChatRoomController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ChatRoomControllerTest.PrincipalResolverConfig.class)
class ChatRoomControllerTest {

  private static final long MEMBER_ID = 100L;
  private static final long ROOM_ID = 10L;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChatRoomService chatRoomService;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @MockitoBean
  @SuppressWarnings("unused")
  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    // addFilters = false 라 인증 필터가 돌지 않으므로, @AuthenticationPrincipal 해석을 위해 principal을 직접 주입한다.
    SecurityContextHolder.getContext()
        .setAuthentication(JwtAuthentication.of(MEMBER_ID, MemberRole.USER));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("내 방 목록 조회 시 200과 방 배열을 반환한다")
  void handleGetMyRooms() throws Exception {
    given(chatRoomService.getMyRooms(MEMBER_ID)).willReturn(List.of(summary()));

    mockMvc.perform(get("/api/chat/rooms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].roomId").value(ROOM_ID))
        .andExpect(jsonPath("$.data[0].unreadCount").value(2));
  }

  @Test
  @DisplayName("방 단건 조회 시 200과 방 요약을 반환한다")
  void handleGetRoom() throws Exception {
    given(chatRoomService.getRoom(ROOM_ID, MEMBER_ID)).willReturn(summary());

    mockMvc.perform(get("/api/chat/rooms/{roomId}", ROOM_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roomId").value(ROOM_ID))
        .andExpect(jsonPath("$.data.progressStatus").value("MATCHED"));
  }

  @Test
  @DisplayName("참여자가 아닌 방 조회 시 403과 NOT_PARTICIPANT를 반환한다")
  void handleGetRoomNotParticipant() throws Exception {
    given(chatRoomService.getRoom(ROOM_ID, MEMBER_ID))
        .willThrow(BaseException.from(ChatErrorCode.NOT_PARTICIPANT));

    mockMvc.perform(get("/api/chat/rooms/{roomId}", ROOM_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_403_NOT_PARTICIPANT"));
  }

  @Test
  @DisplayName("메시지 이력 조회 시 200을 반환하고 커서·크기를 서비스에 전달한다")
  void handleGetMessages() throws Exception {
    given(chatRoomService.getMessageHistory(eq(ROOM_ID), eq(MEMBER_ID), isNull(), anyInt()))
        .willReturn(List.of());

    mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", ROOM_ID))
        .andExpect(status().isOk());

    verify(chatRoomService).getMessageHistory(eq(ROOM_ID), eq(MEMBER_ID), isNull(), anyInt());
  }

  @Test
  @DisplayName("단계 동의 시 200과 갱신된 방 요약을 반환한다")
  void handleAgreeProgress() throws Exception {
    given(chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID))
        .willReturn(summary(ChatRoomProgressStatus.PHOTO_REVEAL_STEP_1));

    mockMvc.perform(post("/api/chat/rooms/{roomId}/progress/agree", ROOM_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.progressStatus").value("PHOTO_REVEAL_STEP_1"));
  }

  @Test
  @DisplayName("이미 동의한 단계 재동의 시 409와 PROGRESS_ALREADY_AGREED를 반환한다")
  void handleAgreeProgressAlreadyAgreed() throws Exception {
    given(chatRoomService.agreeProgress(ROOM_ID, MEMBER_ID))
        .willThrow(BaseException.from(ChatErrorCode.PROGRESS_ALREADY_AGREED));

    mockMvc.perform(post("/api/chat/rooms/{roomId}/progress/agree", ROOM_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHAT_409_PROGRESS_ALREADY_AGREED"));
  }

  @Test
  @DisplayName("채팅방 나가기 시 204를 반환하고 서비스에 위임한다")
  void handleLeaveRoom() throws Exception {
    mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", ROOM_ID))
        .andExpect(status().isNoContent());

    verify(chatRoomService).leaveRoom(ROOM_ID, MEMBER_ID);
  }

  private ChatRoomSummaryResponse summary() {
    return summary(ChatRoomProgressStatus.MATCHED);
  }

  private ChatRoomSummaryResponse summary(ChatRoomProgressStatus progressStatus) {
    return new ChatRoomSummaryResponse(ROOM_ID, ChatRoomStatus.ACTIVE, progressStatus, 99L, 2L);
  }

  /**
   * 보안 자동 구성을 제외했으므로 {@code @AuthenticationPrincipal} 해석용 리졸버를 직접 등록한다.
   * 리졸버는 {@link SecurityContextHolder}의 principal(여기선 memberId)을 컨트롤러 인자로 주입한다.
   */
  @TestConfiguration
  static class PrincipalResolverConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
  }
}
