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

| 요소 | 규칙 | 예시 |
|---|---|---|
| 패키지 | 소문자, 도메인별 구분 | `com.blursome.user` |
| 클래스 | UpperCamelCase, 명사 | `UserService` |
| 인터페이스 | UpperCamelCase, 명사/형용사 | `Notifiable`, `ChatRepository` |
| 메서드 | lowerCamelCase, 동사 | `findByEmail`, `sendNotification` |
| 변수 | lowerCamelCase | `userId`, `chatMessage` |
| 상수 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| Enum 클래스 | UpperCamelCase | `UserStatus` |
| Enum 값 | UPPER_SNAKE_CASE | `UserStatus.ACTIVE` |

### 레이어별 클래스 접미사

| 레이어 | 접미사 | 예시 |
|---|---|---|
| HTTP 어댑터 | `*Controller` | `UserController` |
| 비즈니스 파사드 | `*Service` | `UserService` |
| 데이터 접근 | `*Repository` | `UserRepository` |
| 도메인 엔티티 | 없음 (명사) | `User`, `Chat`, `Notification` |
| 요청 DTO | `*Request` | `LoginRequest`, `SendMessageRequest` |
| 응답 DTO | `*Response` | `UserResponse`, `ChatResponse` |
| 예외 | `*Exception` | `UserNotFoundException` |

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

```json
{
  "success": true,
  "data": { },
  "message": "ok"
}
```

```json
{
  "success": false,
  "data": null,
  "message": "사용자를 찾을 수 없습니다."
}
```

**HTTP 상태 코드 사용 기준**

| 코드 | 상황 |
|---|---|
| 200 OK | 조회, 수정 성공 |
| 201 Created | 리소스 생성 성공 |
| 400 Bad Request | 클라이언트 요청 오류 (유효성 검증 실패 등) |
| 401 Unauthorized | 인증되지 않은 요청 |
| 403 Forbidden | 인가되지 않은 접근 |
| 404 Not Found | 리소스 없음 |
| 500 Internal Server Error | 서버 내부 오류 |

---

## 6. 예외 처리

- 전역 예외 핸들러: `common.exception.GlobalExceptionHandler` (`@RestControllerAdvice`)
- 커스텀 예외 계층: `BlurSomeException` (베이스) → 도메인별 예외

```
BlurSomeException
├── UserNotFoundException
├── ChatNotFoundException
├── NotificationNotFoundException
└── UnauthorizedException
```

**규칙:**
- 모든 커스텀 예외는 에러 코드 enum과 메시지를 포함
- 예외를 조용히 삼키지 않음 (빈 catch 블록 금지)
- 예외 로깅은 적절한 레벨로 — 클라이언트 오류는 WARN, 서버 오류는 ERROR

---

## 7. Lombok 사용 규칙

| 상황 | 사용 방법 |
|---|---|
| 의존성 주입 | `@RequiredArgsConstructor` (생성자 주입) |
| JPA 엔티티 | `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)` |
| DTO | `@Getter` + `@Builder` |
| 값 객체 | `@Getter` + `@EqualsAndHashCode` |

**금지 사항:**
- JPA Entity에 `@Data` 사용 금지 (양방향 관계 `hashCode`/`equals` 무한 루프 위험)
- JPA Entity에 `public` 기본 생성자 사용 금지 (외부에서 불완전한 엔티티 생성 방지)
- `@Autowired` 필드 주입 사용 금지

---

## 8. 테스트 컨벤션

| 테스트 종류 | 애노테이션 | 대상 |
|---|---|---|
| 단위 테스트 | `@ExtendWith(MockitoExtension.class)` | Service, Domain |
| 통합 테스트 | `@SpringBootTest` | 전체 컨텍스트 |
| API 테스트 | `@WebMvcTest` + MockMvc | Controller |

**규칙:**
- 테스트 클래스 네이밍: `<대상클래스>Test` (예: `UserServiceTest`)
- 테스트 메서드에 `@DisplayName` 한국어 허용
- 테스트 메서드는 "given-when-then" 구조로 작성
- Service 레이어 단위 테스트를 우선 작성

**예시**
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
