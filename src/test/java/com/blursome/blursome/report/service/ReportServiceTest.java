package com.blursome.blursome.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.report.domain.Report;
import com.blursome.blursome.report.domain.ReportReason;
import com.blursome.blursome.report.dto.request.ReportRequest;
import com.blursome.blursome.report.exception.ReportErrorCode;
import com.blursome.blursome.report.repository.ReportRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private MemberRepository memberRepository;
  @Mock
  private ChatRoomRepository chatRoomRepository;

  @InjectMocks
  private ReportService reportService;

  private Member member(long id) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + id, "name", "e@e.com", null);
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  private ReportRequest request(Long targetId, Long chatRoomId) {
    return new ReportRequest(targetId, chatRoomId, ReportReason.SPAM, "detail");
  }

  @Test
  @DisplayName("프로필 신고를 접수한다(방 없음)")
  void report_profile_saves() {
    given(memberRepository.findById(2L)).willReturn(Optional.of(member(2L)));
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    reportService.report(1L, request(2L, null));

    verify(reportRepository).saveAndFlush(any(Report.class));
    verify(chatRoomRepository, never()).findById(any());
  }

  @Test
  @DisplayName("자기 신고는 REPORT_SELF_NOT_ALLOWED 예외")
  void report_self_throws() {
    assertThatThrownBy(() -> reportService.report(1L, request(1L, null)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ReportErrorCode.REPORT_SELF_NOT_ALLOWED.getCode());
    verify(reportRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("대상 회원이 없으면 MEMBER_NOT_FOUND 예외")
  void report_targetNotFound_throws() {
    given(memberRepository.findById(99L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> reportService.report(1L, request(99L, null)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", MemberErrorCode.MEMBER_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("채팅방 신고인데 방이 없으면 ROOM_NOT_FOUND 예외")
  void report_roomNotFound_throws() {
    given(memberRepository.findById(2L)).willReturn(Optional.of(member(2L)));
    given(chatRoomRepository.findById(10L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> reportService.report(1L, request(2L, 10L)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("중복 신고(유니크 충돌)는 REPORT_ALREADY_EXISTS 예외")
  void report_duplicate_throws() {
    given(memberRepository.findById(2L)).willReturn(Optional.of(member(2L)));
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(reportRepository.saveAndFlush(any(Report.class)))
        .willThrow(new DataIntegrityViolationException("dup"));

    assertThatThrownBy(() -> reportService.report(1L, request(2L, null)))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ReportErrorCode.REPORT_ALREADY_EXISTS.getCode());
  }

  @Test
  @DisplayName("채팅방 신고가 고유 신고자 3명에 도달하면 방을 동결(REPORTED)한다")
  void report_roomThresholdReached_marksRoomReported() {
    ChatRoom room = mock(ChatRoom.class);
    given(memberRepository.findById(2L)).willReturn(Optional.of(member(2L)));
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(chatRoomRepository.findById(10L)).willReturn(Optional.of(room));
    given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(inv -> inv.getArgument(0));
    given(reportRepository.countDistinctReporters(eq(2L), any(LocalDateTime.class))).willReturn(3L);

    reportService.report(1L, request(2L, 10L));

    verify(room).markReported();
  }

  @Test
  @DisplayName("임계치 미달이면 방을 동결하지 않는다")
  void report_belowThreshold_doesNotMarkRoom() {
    ChatRoom room = mock(ChatRoom.class);
    given(memberRepository.findById(2L)).willReturn(Optional.of(member(2L)));
    given(memberRepository.getReferenceById(1L)).willReturn(member(1L));
    given(chatRoomRepository.findById(10L)).willReturn(Optional.of(room));
    given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(inv -> inv.getArgument(0));
    given(reportRepository.countDistinctReporters(eq(2L), any(LocalDateTime.class))).willReturn(2L);

    reportService.report(1L, request(2L, 10L));

    verify(room, never()).markReported();
  }
}
