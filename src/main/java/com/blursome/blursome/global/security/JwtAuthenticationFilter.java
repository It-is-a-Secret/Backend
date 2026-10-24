package com.blursome.blursome.global.security;

import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.exception.JwtAuthenticationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider jwtTokenProvider;
  private final JwtAuthenticationEntryPoint authenticationEntryPoint;
  private final MemberAccessValidator memberAccessValidator;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
  ) throws ServletException, IOException {
    String token = resolveToken(request);
    if (token != null) {
      try {
        JwtAuthentication authentication = jwtTokenProvider.parseAccess(token);
        // 서명·만료가 유효해도 탈퇴한 회원의 토큰은 보호 API에 쓰지 못하게 막는다(#107).
        memberAccessValidator.validateAccess(authentication.getPrincipal());
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtAuthenticationException e) {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response, e);
        return;
      } catch (BaseException e) {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.writeError(response, e);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
