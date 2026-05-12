package com.blursome.blursome.global.exception;

import com.blursome.blursome.global.exception.code.GlobalErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
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

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

  private static final String TIMESTAMP_PATTERN =
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @RestController
  static class TestController {

    @GetMapping("/test/base-exception")
    public void throwBaseException() {
      throw BaseException.from(GlobalErrorCode.INVALID_REQUEST);
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

  @Getter
  @Setter
  static class TestRequest {

    @NotBlank(message = "이름은 필수입니다.")
    private String name;
  }

  @Test
  @DisplayName("BaseException 발생 시 400과 ErrorResponse를 반환한다")
  void handleBaseException() throws Exception {
    mockMvc.perform(get("/test/base-exception"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Bad Request"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("유효하지 않은 요청입니다."));
  }

  @Test
  @DisplayName("Request Parameter 누락 시 400과 ErrorResponse를 반환한다")
  void handleMissingServletRequestParameterException() throws Exception {
    mockMvc.perform(get("/test/required-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Bad Request"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_PARAMETER_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("필수 요청 파라미터가 누락되었습니다."));
  }

  @Test
  @DisplayName("JSON 형식 오류 시 400과 ErrorResponse를 반환한다")
  void handleHttpMessageNotReadableException() throws Exception {
    mockMvc.perform(
            post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Bad Request"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_INVALID_REQUEST_BODY"))
        .andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
  }

  @Test
  @DisplayName("@Valid 검증 실패 시 400과 reasons 목록을 반환한다")
  void handleMethodArgumentValidation() throws Exception {
    mockMvc.perform(
            post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Bad Request"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID"))
        .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
        .andExpect(jsonPath("$.reasons[0].field").value("name"))
        .andExpect(jsonPath("$.reasons[0].message").value("이름은 필수입니다."))
        .andExpect(jsonPath("$.reasons[0].rejectedValue").value(""));
  }

  @Test
  @DisplayName("NoResourceFoundException 발생 시 404와 ErrorResponse를 반환한다")
  void handleNoResourceFoundException() throws Exception {
    mockMvc.perform(get("/test/no-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("Not Found"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("CLIENT_ERROR_404_RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
  }

  @Test
  @DisplayName("처리되지 않은 예외 발생 시 500과 ErrorResponse를 반환한다")
  void handleUnexpectedException() throws Exception {
    mockMvc.perform(get("/test/server-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value("Internal Server Error"))
        .andExpect(jsonPath("$.timestamp").value(matchesPattern(TIMESTAMP_PATTERN)))
        .andExpect(jsonPath("$.code").value("SERVER_ERROR_500_INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
  }
}
