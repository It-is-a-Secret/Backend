# 코드 컨벤션

## 1. 기본 스타일 가이드

- 기준: **Google Java Style Guide**
- Checkstyle: `.idea/checkstyle-idea.xml`에 Google Checks 구성됨
- 들여쓰기: **2칸 스페이스** (탭 사용 금지)
- 최대 줄 길이: **100자**
- 파일 인코딩: **UTF-8**
- 모든 소스 파일 마지막에 빈 줄 1개

---

## 2. 네이밍 컨벤션

| 요소       | 규칙                     | 예시                                |
|----------|------------------------|-----------------------------------|
| 패키지      | 소문자, 도메인별 구분           | `com.blursome.user`               |
| 클래스      | UpperCamelCase, 명사     | `UserService`                     |
| 인터페이스    | UpperCamelCase, 명사/형용사 | `Notifiable`, `ChatRepository`    |
| 메서드      | lowerCamelCase, 동사     | `findByEmail`, `sendNotification` |
| 변수       | lowerCamelCase         | `userId`, `chatMessage`           |
| 상수       | UPPER_SNAKE_CASE       | `MAX_RETRY_COUNT`                 |
| Enum 클래스 | UpperCamelCase         | `UserStatus`                      |
| Enum 값   | UPPER_SNAKE_CASE       | `UserStatus.ACTIVE`               |

### 레이어별 클래스 접미사

| 레이어      | 접미사           | 예시                                   |
|----------|---------------|--------------------------------------|
| HTTP 어댑터 | `*Controller` | `UserController`                     |
| 비즈니스 파사드 | `*Service`    | `UserService`                        |
| 데이터 접근   | `*Repository` | `UserRepository`                     |
| 도메인 엔티티  | 없음 (명사)       | `User`, `Chat`, `Notification`       |
| 요청 DTO   | `*Request`    | `LoginRequest`, `SendMessageRequest` |
| 응답 DTO   | `*Response`   | `UserResponse`, `ChatResponse`       |
| 예외       | `*Exception`  | `UserNotFoundException`              |

> DTO에 엔티티를 직접 노출하지 않습니다. 항상 `*Request` / `*Response`로 변환합니다.

---

## 3. 패키지 구조 규칙

전체 구조는 [`docs/ARCHITECTURE.md § 패키지 구조`](ARCHITECTURE.md#3-패키지-구조) 참고.

**규칙:**

- 각 도메인 패키지는 자급자족 — 해당 도메인의 Entity, Repository, Service, Controller를 모두 포함
- 도메인 간 접근은 반드시 **Service 레이어**를 경유 (다른 도메인의 Repository 직접 주입 금지)
- `common` 패키지는 진정으로 공유되는 인프라만 포함 (전역 예외 핸들러, 응답 래퍼, 공통 설정 빈)
- 도메인에 귀속되는 클래스를 `common`에 두지 않음

---

## 4. 레이어 규칙

아키텍처 배경은 [`docs/ARCHITECTURE.md § Facade 패턴`](ARCHITECTURE.md#4-facade-패턴) 참고.

**Controller**

- Repository를 직접 주입하거나 호출하지 않음
- 비즈니스 if/else 로직을 갖지 않음
- HTTP 요청/응답 변환만 담당 (DTO 바인딩, `@Valid` 검증, 응답 래핑)
- `@Transactional` 사용 금지

**Service**

- 트랜잭션 경계 관리 (`@Transactional`)
- Repository 호출 및 도메인 로직 조율
- 도메인 간 조율이 필요한 경우 다른 Service를 주입
- Entity → Response DTO 변환 담당

**Repository**

- 데이터 접근 로직만 포함
- 비즈니스 로직 없음
- `JpaRepository<Entity, Long>` 상속

**Domain Entity**

- 자신의 불변 조건을 강제하는 메서드 보유 (Rich Domain Model)
- Setter 메서드 노출 금지 — 의미 있는 도메인 메서드로 상태 변경

---

## 5. API 응답 컨벤션

모든 API 응답은 `common.response.ApiResponse<T>` 래퍼를 사용합니다.

`ApiResponse`는 Java record로 구현되며 `code`, `message`, `data` 세 필드를 가집니다.

**성공 응답**

```json
{
  "code": 200,
  "message": "ok",
  "data": {}
}
```

**단순 오류 응답**

```json
{
  "code": 404,
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

**`@Valid` 검증 실패 응답** — `data`에 `ErrorDetail` 목록 반환

```json
{
  "code": 400,
  "message": "요청 값이 올바르지 않습니다.",
  "data": [
    {
      "field": "name",
      "message": "이름은 필수입니다.",
      "rejectedValue": ""
    }
  ]
}
```

**팩토리 메서드**

| 메서드                                               | 용도        |
|---------------------------------------------------|-----------|
| `ApiResponse.response(HttpStatus, message, data)` | 데이터 포함 응답 |
| `ApiResponse.response(HttpStatus, message)`       | 데이터 없는 응답 |

**HTTP 상태 코드 사용 기준**

| 코드                        | 상황                        |
|---------------------------|---------------------------|
| 200 OK                    | 조회, 수정 성공                 |
| 201 Created               | 리소스 생성 성공                 |
| 400 Bad Request           | 클라이언트 요청 오류 (유효성 검증 실패 등) |
| 401 Unauthorized          | 인증되지 않은 요청                |
| 403 Forbidden             | 인가되지 않은 접근                |
| 404 Not Found             | 리소스 없음                    |
| 500 Internal Server Error | 서버 내부 오류                  |

---

## 6. 예외 처리

- 전역 예외 핸들러: `common.exception.GlobalExceptionHandler` (`@RestControllerAdvice`)
- 커스텀 예외 계층: `BaseException` (베이스) → 도메인별 예외

```
BaseException
├── UserNotFoundException
├── ChatNotFoundException
├── NotificationNotFoundException
└── UnauthorizedException
```

**ErrorCode enum** — 모든 커스텀 예외는 `ErrorCode`를 통해 HTTP 상태 코드와 메시지를 보유합니다.

| ErrorCode                   | HTTP 상태 | 메시지                  |
|-----------------------------|---------|----------------------|
| `USER_NOT_FOUND`            | 404     | 사용자를 찾을 수 없습니다.      |
| `CHAT_NOT_FOUND`            | 404     | 채팅을 찾을 수 없습니다.       |
| `NOTIFICATION_NOT_FOUND`    | 404     | 알림을 찾을 수 없습니다.       |
| `UNAUTHORIZED`              | 401     | 인증되지 않은 요청입니다.       |
| `PARAMETER_NOT_FOUND`       | 400     | 필수 요청 파라미터가 누락되었습니다. |
| `INVALID_REQUEST_BODY`      | 400     | 요청 본문 형식이 올바르지 않습니다. |
| `METHOD_ARGUMENT_NOT_VALID` | 400     | 요청 값이 올바르지 않습니다.     |
| `RESOURCE_NOT_FOUND`        | 404     | 요청한 리소스를 찾을 수 없습니다.  |
| `INTERNAL_SERVER_ERROR`     | 500     | 서버 내부 오류가 발생했습니다.    |

**GlobalExceptionHandler 처리 목록**

| 예외 클래스                                    | 로그 레벨 | 응답                               |
|-------------------------------------------|-------|----------------------------------|
| `BaseException`                           | WARN  | `ErrorCode`의 상태·메시지              |
| `MissingServletRequestParameterException` | WARN  | 400, `PARAMETER_NOT_FOUND`       |
| `HttpMessageNotReadableException`         | WARN  | 400, `INVALID_REQUEST_BODY`      |
| `MethodArgumentNotValidException`         | WARN  | 400, `data`에 `List<ErrorDetail>` |
| `NoResourceFoundException`                | WARN  | 404, `RESOURCE_NOT_FOUND`        |
| `Exception` (미처리)                         | ERROR | 500, `INTERNAL_SERVER_ERROR`     |

**ErrorDetail** — `@Valid` 검증 실패 시 필드별 오류 정보를 담는 응답 객체 (`common.response.ErrorDetail`)

```java
ErrorDetail.of(field, message, rejectedValue)
```

**규칙:**

- 모든 커스텀 예외는 `ErrorCode`를 인자로 받아 `BaseException`을 상속
- 예외를 조용히 삼키지 않음 (빈 catch 블록 금지)
- 예외 로깅은 적절한 레벨로 — 클라이언트 오류는 WARN, 서버 오류는 ERROR

---

## 7. Lombok 사용 규칙

| 상황      | 사용 방법                                                            |
|---------|------------------------------------------------------------------|
| 의존성 주입  | `@RequiredArgsConstructor` (생성자 주입)                              |
| JPA 엔티티 | `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)` |
| DTO     | `@Getter` + `@Builder`                                           |
| 값 객체    | `@Getter` + `@EqualsAndHashCode`                                 |

**금지 사항:**

- JPA Entity에 `@Data` 사용 금지 (양방향 관계 `hashCode`/`equals` 무한 루프 위험)
- JPA Entity에 `public` 기본 생성자 사용 금지 (외부에서 불완전한 엔티티 생성 방지)
- `@Autowired` 필드 주입 사용 금지

---

## 8. 테스트 컨벤션

| 테스트 종류             | 방식                                    | 대상                               |
|--------------------|---------------------------------------|----------------------------------|
| 단위 테스트             | `@ExtendWith(MockitoExtension.class)` | Service, Domain                  |
| 통합 테스트             | `@SpringBootTest`                     | 전체 컨텍스트                          |
| Controller API 테스트 | `@WebMvcTest` + MockMvc               | Controller 요청·응답 검증              |
| 인프라 단위 테스트         | `MockMvcBuilders.standaloneSetup()`   | `@RestControllerAdvice` 등 공통 인프라 |

**`@WebMvcTest` vs `standaloneSetup` 선택 기준**

| 상황                                      | 방식                                                                      |
|-----------------------------------------|-------------------------------------------------------------------------|
| 특정 Controller의 요청·응답·보안 검증              | `@WebMvcTest(controllers = XxxController.class)`                        |
| `GlobalExceptionHandler` 등 공통 인프라 단독 검증 | `MockMvcBuilders.standaloneSetup(...).setControllerAdvice(...).build()` |

> `standaloneSetup`은 Spring 컨텍스트 없이 대상 컴포넌트만 올려 검증하므로 속도가 빠릅니다.
> Controller 테스트에서 예외 응답 형식을 검증할 때는 `@WebMvcTest`를 사용합니다.

**규칙:**

- 테스트 클래스 네이밍: `<대상클래스>Test` (예: `UserServiceTest`)
- 테스트 메서드에 `@DisplayName` 한국어 허용
- Service 레이어 단위 테스트를 우선 작성

**테스트 종류별 작성 방식**

| 종류                            | 메서드 네이밍                           | 구조                                           |
|-------------------------------|-----------------------------------|----------------------------------------------|
| 단위 테스트 (Service, Domain)      | `method_whenCondition_thenResult` | `// given` `// when` `// then` 주석 명시         |
| MockMvc 테스트 (Controller, 인프라) | `handle<대상시나리오>`                  | `perform()` → `andExpect()` 체이닝으로 표현 (주석 생략) |

**단위 테스트 예시**

```java

@Test
@DisplayName("존재하지 않는 사용자 조회 시 UserNotFoundException이 발생한다")
void findUser_whenUserNotFound_throwsException() {
  // given
  given(userRepository.findById(anyLong())).willReturn(Optional.empty());

  // when & then
  assertThatThrownBy(() -> userService.findById(1L))
      .isInstanceOf(UserNotFoundException.class);
}
```

**MockMvc 테스트 예시** (`standaloneSetup` / `@WebMvcTest` 공통)

```java

@BeforeEach
void setUp() {                                        // given: 픽스처 구성
  mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
}

@Test
@DisplayName("UserNotFoundException 발생 시 404와 ApiResponse를 반환한다")
void handleUserNotFoundException() throws Exception {
  mockMvc.perform(get("/test/user-not-found"))        // when
      .andExpect(status().isNotFound())               // then
      .andExpect(jsonPath("$.code").value(404))
      .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
}
```

---

## 9. IntelliJ IDEA 설정 가이드

이 섹션에서는 위 컨벤션을 IntelliJ IDEA에서 자동으로 강제하는 방법을 설명합니다.

---

### 9.1 Code Style — Google Java Style 적용

이 프로젝트의 코드 스타일 설정은 `.idea/` 폴더에 이미 포함되어 있습니다. 저장소를 클론하면 IntelliJ가 자동으로 읽어들입니다. 적용이 안 됐거나 재확인이 필요할 때
아래 절차를 따릅니다.

**자동 적용 확인**

`Settings` → `Editor` → `Code Style` → `Java` 에서 Scheme이 **Project** 로 선택되어 있는지 확인합니다.

**수동으로 XML 가져오기 (재설정 필요 시)**

1. [intellij-java-google-style.xml](https://github.com/google/styleguide/blob/gh-pages/intellij-java-google-style.xml)
   다운로드
2. `Settings` → `Editor` → `Code Style` → `Java`
3. 오른쪽 상단 톱니바퀴 아이콘 → `Import Scheme` → `IntelliJ IDEA code style XML`
4. 다운로드한 파일 선택

---

### 9.2 들여쓰기 및 줄 길이 확인

`Settings` → `Editor` → `Code Style` → `Java`

| 탭                       | 항목                     | 값               |
|-------------------------|------------------------|-----------------|
| **Tabs and Indents**    | Use tab character      | **OFF (체크 해제)** |
| **Tabs and Indents**    | Tab size               | `2`             |
| **Tabs and Indents**    | Indent                 | `2`             |
| **Tabs and Indents**    | Continuation indent    | `4`             |
| **Wrapping and Braces** | Right margin (columns) | `100`           |

> `Hard wrap at`을 `100`으로 설정하면 에디터 오른쪽에 가이드 선이 표시됩니다.
> `Settings` → `Editor` → `Code Style` → `Hard wrap at` 항목도 동일하게 `100`으로 맞춥니다.

---

### 9.3 Checkstyle 플러그인 설정

**플러그인 설치**

`Settings` → `Plugins` → Marketplace 탭에서 `CheckStyle-IDEA` 검색 후 설치 → IDE 재시작

**설정 파일 연결**

1. `Settings` → `Tools` → `Checkstyle`
2. 목록에 이미 있는 **`Google Checks`** 항목 왼쪽 체크박스를 **활성화**
3. `Scan Scope`: `All sources including tests`

**실행 방법**

- 에디터 하단 `Checkstyle` 탭 → `Check Current File` 또는 `Check Project`
- 위반 항목을 클릭하면 해당 코드 라인으로 이동

---

### 9.4 파일 인코딩 설정

`Settings` → `Editor` → `File Encodings`

| 항목                                    | 값             |
|---------------------------------------|---------------|
| Global Encoding                       | `UTF-8`       |
| Project Encoding                      | `UTF-8`       |
| Default encoding for properties files | `UTF-8`       |
| Create UTF-8 files                    | `with NO BOM` |

---

### 9.5 저장 시 자동 포맷 (Actions on Save)

`Settings` → `Tools` → `Actions on Save`

| 항목               | 설정          |
|------------------|-------------|
| Reformat code    | **ON**      |
| Optimize imports | **ON**      |
| Rearrange code   | OFF (선택 사항) |
| Run code cleanup | OFF (선택 사항) |

> 이 설정을 켜두면 파일 저장(Ctrl+S)마다 들여쓰기, 공백, import 순서가 자동 정렬됩니다.

---

### 9.6 Import 순서 설정

`Settings` → `Editor` → `Code Style` → `Java` → `Imports` 탭

| 항목                                        | 값                       |
|-------------------------------------------|-------------------------|
| Class count to use import with '*'        | `999` (와일드카드 import 방지) |
| Names count to use static import with '*' | `999`                   |

Import 블록 순서 (`Import Layout`):

```
import java.*
import javax.*
<blank line>
import org.*
import com.*
<blank line>
import static all other imports
<blank line>
import all other imports
```

> Google Java Style은 알파벳 순서의 단일 블록을 권장합니다. 위 설정이 자동으로 적용됩니다.

---

### 9.7 EditorConfig 지원 활성화

EditorConfig는 에디터 무관하게 들여쓰기·인코딩 설정을 공유합니다. IntelliJ는 기본적으로 `.editorconfig`를 지원합니다.

**활성화 확인**: `Settings` → `Editor` → `Code Style` → `Enable EditorConfig support` 체크

프로젝트 루트에 `.editorconfig` 파일이 없다면 아래 내용으로 생성합니다:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 2
trim_trailing_whitespace = true
insert_final_newline = true

[*.java]
indent_size = 2
max_line_length = 100

[*.{yml,yaml}]
indent_size = 2

[*.{json}]
indent_size = 2

[*.gradle.kts]
indent_size = 4
```

---

### 9.8 자주 사용하는 포맷 단축키

| 작업            | Windows / Linux                                | macOS              |
|---------------|------------------------------------------------|--------------------|
| 코드 재포맷        | `Ctrl + Alt + L`                               | `Cmd + Option + L` |
| Import 최적화    | `Ctrl + Alt + O`                               | `Cmd + Option + O` |
| 파일 전체 정리      | `Ctrl + Alt + L` (전체 선택 후)                     | `Cmd + Option + L` |
| Checkstyle 실행 | 하단 `Checkstyle` 탭                              | 하단 `Checkstyle` 탭  |
| 줄 길이 초과 확인    | `View` → `Active Editor` → `Show Right Margin` | 동일                 |

---

### 9.9 Gradle Checkstyle 태스크 (CI 연동)

로컬 IDE 설정과 별개로, CI에서도 동일한 규칙을 강제합니다.

```bash
# 전체 소스 Checkstyle 검사
./gradlew checkstyleMain checkstyleTest

# 위반 시 빌드 실패 — 커밋 전 로컬에서도 실행 권장
```

`build.gradle.kts`에 Checkstyle 태스크가 구성되어 있으면 `./gradlew build` 시 자동 실행됩니다.
