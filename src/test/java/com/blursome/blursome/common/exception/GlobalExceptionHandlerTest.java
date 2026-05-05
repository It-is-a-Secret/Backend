package com.blursome.blursome.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    controllers = GlobalExceptionHandlerTest.TestController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
class GlobalExceptionHandlerTest {

  @Autowired
  private MockMvc mockMvc;

  @RestController
  static class TestController {

    @GetMapping("/test/user-not-found")
    public void throwUserNotFoundException() {
      throw new UserNotFoundException();
    }

    @GetMapping("/test/unauthorized")
    public void throwUnauthorizedException() {
      throw new UnauthorizedException();
    }

    @GetMapping("/test/server-error")
    public void throwServerError() {
      throw new RuntimeException("예상치 못한 오류");
    }
  }

  @Test
  @DisplayName("UserNotFoundException 발생 시 404와 ApiResponse를 반환한다")
  void handleUserNotFoundException() throws Exception {
    mockMvc.perform(get("/test/user-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
  }

  @Test
  @DisplayName("UnauthorizedException 발생 시 401과 ApiResponse를 반환한다")
  void handleUnauthorizedException() throws Exception {
    mockMvc.perform(get("/test/unauthorized"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("인증되지 않은 요청입니다."));
  }

  @Test
  @DisplayName("처리되지 않은 예외 발생 시 500과 ApiResponse를 반환한다")
  void handleUnexpectedException() throws Exception {
    mockMvc.perform(get("/test/server-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
  }
}
