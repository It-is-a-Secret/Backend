package com.blursome.blursome.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

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

    @GetMapping("/test/required-param")
    public void requiredParam(@RequestParam String keyword) {
    }

    @PostMapping("/test/valid")
    public void validRequest(@Valid @RequestBody TestRequest request) {
    }

    @GetMapping("/test/no-resource")
    public void throwNoResourceFoundException() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/missing");
    }

    @GetMapping("/test/server-error")
    public void throwServerError() {
      throw new RuntimeException("unexpected error");
    }
  }

  static class TestRequest {

    @NotBlank(message = "이름은 필수입니다.")
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("UserNotFoundException 발생 시 404와 ApiResponse를 반환한다")
  void handleUserNotFoundException() throws Exception {
    mockMvc.perform(get("/test/user-not-found")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
  }

  @Test
  @DisplayName("UnauthorizedException 발생 시 401과 ApiResponse를 반환한다")
  void handleUnauthorizedException() throws Exception {
    mockMvc.perform(get("/test/unauthorized")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("인증되지 않은 요청입니다."));
  }

  @Test
  @DisplayName("Request Parameter 누락 시 400과 ApiResponse를 반환한다")
  void handleMissingServletRequestParameterException() throws Exception {
    mockMvc.perform(get("/test/required-param")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("필수 요청 파라미터가 누락되었습니다."));
  }

  @Test
  @DisplayName("JSON 형식 오류 시 400과 ApiResponse를 반환한다")
  void handleHttpMessageNotReadableException() throws Exception {
    mockMvc.perform(
            post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
  }

  @Test
  @DisplayName("@Valid 검증 실패 시 400과 필드별 ErrorDetail을 반환한다")
  void handleMethodArgumentValidation() throws Exception {
    mockMvc.perform(
            post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
        .andExpect(jsonPath("$.data[0].field").value("name"))
        .andExpect(jsonPath("$.data[0].message").value("이름은 필수입니다."))
        .andExpect(jsonPath("$.data[0].rejectedValue").value(""));
  }

  @Test
  @DisplayName("NoResourceFoundException 발생 시 404와 ApiResponse를 반환한다")
  void handleNoResourceFoundException() throws Exception {
    mockMvc.perform(get("/test/no-resource")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
  }

  @Test
  @DisplayName("처리되지 않은 예외 발생 시 500과 ApiResponse를 반환한다")
  void handleUnexpectedException() throws Exception {
    mockMvc.perform(get("/test/server-error")).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
  }
}
