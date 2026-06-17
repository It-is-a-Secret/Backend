package com.blursome.blursome.member.verification;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.exception.MemberErrorCode;
import org.springframework.stereotype.Component;

/**
 * 학교 이메일 정책. 인정 도메인(안양대학교 {@code @gs.anyang.ac.kr})만 인증을 허용한다.
 *
 * <p>예: {@code example@gs.anyang.ac.kr} 은 허용, 그 외 도메인은 거부. 비교는 도메인부의
 * 대소문자를 무시한다. 인정 도메인 화이트리스트가 늘어나면 {@link #ALLOWED_DOMAIN}을 집합으로 확장한다.
 */
@Component
public class SchoolEmailPolicy {

  /** 안양대학교 학생 이메일 도메인. */
  static final String ALLOWED_DOMAIN = "gs.anyang.ac.kr";

  /**
   * 이메일이 인정 학교 도메인인지 검증한다.
   *
   * @param email 검증할 이메일 주소
   * @throws BaseException 형식이 잘못되었거나 인정 도메인이 아닌 경우
   *     ({@link MemberErrorCode#MEMBER_INVALID_SCHOOL_EMAIL_DOMAIN})
   */
  public void validate(String email) {
    if (!isAllowed(email)) {
      throw BaseException.from(MemberErrorCode.MEMBER_INVALID_SCHOOL_EMAIL_DOMAIN);
    }
  }

  private boolean isAllowed(String email) {
    if (email == null) {
      return false;
    }
    int at = email.lastIndexOf('@');
    if (at < 0) {
      return false;
    }
    String domain = email.substring(at + 1);
    return ALLOWED_DOMAIN.equalsIgnoreCase(domain);
  }
}
