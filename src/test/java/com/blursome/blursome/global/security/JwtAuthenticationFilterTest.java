package com.blursome.blursome.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.member.domain.MemberRole;
import com.blursome.blursome.member.exception.MemberErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** REST 인증 필터가 탈퇴 회원의 유효한 Access Token을 막는지 검증한다(#107). */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  private static final Long MEMBER_ID = 1L;

  @Mock
  private JwtTokenProvider jwtTokenProvider;

  @Mock
  private MemberAccessValidator memberAccessValidator;

  @Mock
  private FilterChain filterChain;

  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    filter = new JwtAuthenticationFilter(jwtTokenProvider,
        new JwtAuthenticationEntryPoint(objectMapper), memberAccessValidator);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("탈퇴 회원의 유효한 토큰이면 403 MEMBER_403_INACTIVE로 응답하고 요청을 넘기지 않는다")
  void doFilter_whenWithdrawnMember_thenRespondsForbidden() throws Exception {
    // given
    given(jwtTokenProvider.parseAccess("token"))
        .willReturn(JwtAuthentication.of(MEMBER_ID, MemberRole.USER));
    willThrow(BaseException.from(MemberErrorCode.MEMBER_INACTIVE))
        .given(memberAccessValidator).validateAccess(MEMBER_ID);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    filter.doFilter(requestWithToken("token"), response, filterChain);

    // then
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("MEMBER_403_INACTIVE");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("활성 회원의 토큰이면 인증 정보를 채우고 요청을 넘긴다")
  void doFilter_whenActiveMember_thenAuthenticatesAndContinues() throws Exception {
    // given
    JwtAuthentication authentication = JwtAuthentication.of(MEMBER_ID, MemberRole.USER);
    given(jwtTokenProvider.parseAccess("token")).willReturn(authentication);
    MockHttpServletRequest request = requestWithToken("token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    filter.doFilter(request, response, filterChain);

    // then
    verify(memberAccessValidator).validateAccess(MEMBER_ID);
    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
  }

  @Test
  @DisplayName("토큰이 없으면 회원 상태를 확인하지 않고 요청을 넘긴다")
  void doFilter_whenNoToken_thenSkipsMemberCheck() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    filter.doFilter(request, response, filterChain);

    // then
    verify(memberAccessValidator, never()).validateAccess(any());
    verify(filterChain).doFilter(request, response);
  }

  private MockHttpServletRequest requestWithToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    return request;
  }
}
