package com.blursome.blursome.report.service;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.report.domain.Report;
import com.blursome.blursome.report.dto.request.ReportRequest;
import com.blursome.blursome.report.exception.ReportErrorCode;
import com.blursome.blursome.report.repository.ReportRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고(report) 서비스. 신고를 접수하고, 대상별 <b>고유 신고자 수</b>(최근 {@value #REPORT_WINDOW_DAYS}일)를
 * 집계해 임계치에 따른 연동을 수행한다(마스터 §6-17).
 *
 * <p>임계치(마스터 §6-17): 1명=검토 큐 / 3명=발신 제한(채팅방 동결) / 5명+=회원 임시 정지. 채팅방 신고가
 * 3명에 도달하면 해당 방을 {@code REPORTED}로 동결하고(비활성→송신·조회 차단), 대상의 고유 신고자가 5명에
 * 도달하면 회원을 {@code SUSPENDED}로 <b>자동 정지</b>한다(#75 — 정지 회원은 로그인·채팅·탐색에서 차단).
 * 1명=검토 큐(운영자 도구)와 발신 제한 단방향 정밀화는 후속 과제다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

  private static final int REPORT_WINDOW_DAYS = 7;
  /** 발신 제한(채팅방 동결) 임계치 — 고유 신고자 3명. */
  private static final int ROOM_FREEZE_THRESHOLD = 3;
  /** 회원 임시 정지(SUSPENDED) 임계치 — 고유 신고자 5명(#75). */
  private static final int MEMBER_SUSPEND_THRESHOLD = 5;

  private final ReportRepository reportRepository;
  private final MemberRepository memberRepository;
  private final ChatRoomRepository chatRoomRepository;

  /**
   * 신고를 접수한다. 자기 신고는 거부하고, 동일 (신고자, 대상, 방) 중복 신고는 거절한다. 접수 후 대상의 고유
   * 신고자 수를 집계해 임계치별 제재를 적용한다: 채팅방 신고가 3명에 도달하면 해당 방을 동결
   * ({@code REPORTED})하고, 5명에 도달하면 대상 회원을 임시 정지({@code SUSPENDED})한다(#75).
   *
   * @throws BaseException 자기 신고({@link ReportErrorCode#REPORT_SELF_NOT_ALLOWED}), 대상 없음
   *     ({@link MemberErrorCode#MEMBER_NOT_FOUND}), 방 없음({@link ChatErrorCode#ROOM_NOT_FOUND}),
   *     중복 신고({@link ReportErrorCode#REPORT_ALREADY_EXISTS})
   */
  @Transactional
  public void report(Long reporterId, ReportRequest request) {
    if (reporterId.equals(request.targetMemberId())) {
      throw BaseException.from(ReportErrorCode.REPORT_SELF_NOT_ALLOWED);
    }
    Member target = memberRepository.findById(request.targetMemberId())
        .orElseThrow(() -> BaseException.from(MemberErrorCode.MEMBER_NOT_FOUND));

    ChatRoom chatRoom = null;
    if (request.chatRoomId() != null) {
      chatRoom = chatRoomRepository.findById(request.chatRoomId())
          .orElseThrow(() -> BaseException.from(ChatErrorCode.ROOM_NOT_FOUND));
    }

    Member reporter = memberRepository.getReferenceById(reporterId);
    try {
      reportRepository.saveAndFlush(
          Report.create(reporter, target, chatRoom, request.reason(), request.detail()));
    } catch (DataIntegrityViolationException e) {
      throw BaseException.from(ReportErrorCode.REPORT_ALREADY_EXISTS);
    }

    long distinctReporters = reportRepository.countDistinctReporters(
        request.targetMemberId(), LocalDateTime.now().minusDays(REPORT_WINDOW_DAYS));
    if (chatRoom != null && distinctReporters >= ROOM_FREEZE_THRESHOLD) {
      chatRoom.markReported();
    }
    // 고유 신고자 5명 도달 시 회원 자동 임시 정지(멱등). 프로필·채팅방 신고 모두 회원 단위로 집계된다.
    if (distinctReporters >= MEMBER_SUSPEND_THRESHOLD) {
      target.suspend();
    }
  }
}
