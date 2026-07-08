package com.blursome.blursome.report.repository;

import com.blursome.blursome.report.domain.Report;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

  /**
   * 대상({@code targetId})에 대한 <b>고유 신고자 수</b>를 {@code since} 이후 신고에서 집계한다(임계치 판정용).
   * 동일 신고자의 반복 신고는 1로 집계된다(마스터 §6-17).
   */
  @Query("select count(distinct r.reporter.id) from Report r "
      + "where r.target.id = :targetId and r.createdAt >= :since")
  long countDistinctReporters(@Param("targetId") Long targetId, @Param("since") LocalDateTime since);
}
