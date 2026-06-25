package com.blursome.blursome.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.report.domain.Report;
import com.blursome.blursome.report.domain.ReportReason;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link ReportRepository}의 고유 신고자 집계와 {@code uk_report_reporter_target_room} 중복 차단을
 * MySQL(Testcontainers)로 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ReportRepositoryTest {

  @Autowired
  private ReportRepository reportRepository;

  @Autowired
  private TestEntityManager em;

  private Member persistMember(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    em.persist(member);
    return member;
  }

  private ChatRoom persistRoom(Member a, Member b) {
    ChatRoom room = ChatRoom.createOnMatched(a.getId(), b.getId());
    em.persist(room);
    return room;
  }

  @Test
  @DisplayName("고유 신고자 수는 동일 신고자의 반복 신고를 1로 집계한다")
  void countDistinctReporters_dedupesSameReporter() {
    Member target = persistMember("t");
    Member a = persistMember("a");
    Member b = persistMember("b");
    ChatRoom room = persistRoom(a, target);
    reportRepository.save(Report.create(a, target, null, ReportReason.SPAM, null));
    reportRepository.save(Report.create(b, target, null, ReportReason.SPAM, null));
    // 같은 신고자 a의 방 신고(다른 행) — 고유 집계에서는 a 1명으로 흡수
    reportRepository.save(Report.create(a, target, room, ReportReason.INAPPROPRIATE_CHAT, null));
    em.flush();
    em.clear();

    long count = reportRepository.countDistinctReporters(
        target.getId(), LocalDateTime.now().minusDays(7));

    assertThat(count).isEqualTo(2);
  }

  @Test
  @DisplayName("동일 (신고자, 대상, 방) 중복 신고는 유니크 위반으로 막힌다")
  void duplicateRoomReport_violatesUnique() {
    Member target = persistMember("t");
    Member reporter = persistMember("r");
    ChatRoom room = persistRoom(reporter, target);
    reportRepository.saveAndFlush(Report.create(reporter, target, room, ReportReason.SPAM, null));

    assertThatThrownBy(() -> reportRepository.saveAndFlush(
        Report.create(reporter, target, room, ReportReason.ETC, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
