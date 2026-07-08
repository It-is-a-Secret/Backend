package com.blursome.blursome.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DepartmentTest {

  @Test
  @DisplayName("모든 학과는 소속 계열(College)과 한글 라벨을 가진다")
  void everyDepartmentHasCollegeAndLabel() {
    for (Department department : Department.values()) {
      assertThat(department.getCollege()).isNotNull();
      assertThat(department.getLabel()).isNotBlank();
    }
  }

  @Test
  @DisplayName("동일 학과 판정(+1.0 가산 기준)은 enum 동등 비교로 성립한다")
  void sameDepartment_isEnumEquality() {
    assertThat(Department.COMPUTER_ENGINEERING == Department.COMPUTER_ENGINEERING).isTrue();
    assertThat(Department.COMPUTER_ENGINEERING == Department.SOFTWARE).isFalse();
  }

  @Test
  @DisplayName("동일 계열 판정(+0.5 가산 기준)은 getCollege() 비교로 성립한다")
  void sameCollege_isComparedByCollege() {
    // 컴퓨터공학과·소프트웨어학과는 같은 창의융합대학 → 동일 계열
    assertThat(Department.COMPUTER_ENGINEERING.getCollege())
        .isEqualTo(Department.SOFTWARE.getCollege())
        .isEqualTo(College.CREATIVE_CONVERGENCE);

    // 신학과(신학대학)와는 계열이 다르다
    assertThat(Department.COMPUTER_ENGINEERING.getCollege())
        .isNotEqualTo(Department.THEOLOGY.getCollege());
  }
}
