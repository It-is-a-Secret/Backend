# API 응답 컨벤션

## 1. 개요

API 응답은 결과 유형에 따라 타입을 분리합니다.

| 유형 | 클래스 | 설명 |
|---|---|---|
| 성공 응답 | `DataResponse<T>` | 200·201·204 등 정상 처리 |
| 오류 응답 | `ErrorResponse` | 4xx·5xx 예외 처리 |
| 공통 메타데이터 | `BaseResponse` | 두 타입의 상위 클래스 |

Controller는 성공 응답에 `DataResponse<T>`를 직접 생성합니다.
오류 응답은 `GlobalExceptionHandler`가 자동으로 `ErrorResponse`를 생성하므로, Controller·Service에서 직접 만들지 않습니다.

---

## 2. 공통 필드

`DataResponse`와 `ErrorResponse`는 모두 `BaseResponse`를 상속합니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `String` | HTTP 상태 문구 (예: `OK`, `Bad Request`, `Not Found`) |
| `timestamp` | `String` | 응답 생성 시각 (RFC3339 형식, KST 기준) |

```json
{
  "status": "OK",
  "timestamp": "2026-05-08T14:30:12.123+09:00"
}
```

---

## 3. 성공 응답

### 3.1 응답 바디

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | `String` | O | HTTP 상태 문구 |
| `timestamp` | `String` | O | 응답 생성 시각 |
| `data` | `T` | X | 실제 응답 데이터 (`null`이면 직렬화에서 제외) |

### 3.2 데이터 포함 성공 응답

데이터를 포함할 때는 `DataResponse.from(data)`를 사용합니다.

```java
@GetMapping("/users/{userId}")
public ResponseEntity<DataResponse<UserResponse>> getUser(@PathVariable Long userId) {
  UserResponse response = userService.getUser(userId);
  return ResponseEntity.ok(DataResponse.from(response));
}
```

```json
{
  "status": "OK",
  "timestamp": "2026-05-08T14:30:12.123+09:00",
  "data": {
    "id": 1,
    "name": "blur"
  }
}
```

### 3.3 데이터 없는 성공 응답

응답 바디가 필요 없는 명령형 API(삭제 등)는 아래 기준으로 선택합니다.

| 상황 | 권장 응답 |
|---|---|
| 클라이언트가 바디를 필요로 할 때 | `200 OK` + `DataResponse.ok()` |
| 클라이언트가 바디를 필요로 하지 않을 때 | `204 No Content` + `ResponseEntity.noContent().build()` |

`204 No Content`에 JSON 바디를 함께 반환하지 않습니다.

```java
@DeleteMapping("/users/{userId}")
public ResponseEntity<Void> deleteUser(@PathVariable Long userId) {
  userService.deleteUser(userId);
  return ResponseEntity.noContent().build();
}
```

---

## 4. 오류 응답

### 4.1 응답 바디

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | `String` | O | HTTP 상태 문구 |
| `timestamp` | `String` | O | 응답 생성 시각 |
| `message` | `String` | O | 클라이언트에게 전달하는 오류 메시지 |
| `code` | `String` | O | 애플리케이션 오류 코드 |
| `reasons` | `List<ErrorDetail>` | X | 검증 실패 필드별 상세 정보 (`null`이면 직렬화에서 제외) |

### 4.2 일반 오류 응답

필드 레벨 상세 정보가 없는 경우 `ErrorResponse.from(errorCode)`를 사용합니다.

```json
{
  "status": "Not Found",
  "timestamp": "2026-05-08T14:30:12.123+09:00",
  "message": "사용자를 찾을 수 없습니다.",
  "code": "USER_404_NOT_FOUND"
}
```

### 4.3 검증 실패 오류 응답 (`@Valid`)

`@Valid` 검증 실패 시 `ErrorResponse.of(errorCode, reasons)`를 사용하며, `reasons`에 필드별 오류 목록이 포함됩니다.

```json
{
  "status": "Bad Request",
  "timestamp": "2026-05-08T14:30:12.123+09:00",
  "message": "요청 값이 올바르지 않습니다.",
  "code": "CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID",
  "reasons": [
    {
      "field": "name",
      "message": "이름은 필수입니다.",
      "rejectedValue": ""
    }
  ]
}
```

---

## 5. 에러 코드 규칙

애플리케이션 에러 코드는 클라이언트가 의존하므로 한 번 정의된 코드는 변경하지 않습니다.

### 5.1 형식

```
{SCOPE}_{HTTP_상태코드}_{이유}
```

**SCOPE 기준**

| SCOPE | 사용 시점 |
|---|---|
| `CLIENT_ERROR` | 전역 4xx 오류 (잘못된 요청, 리소스 없음 등) |
| `SERVER_ERROR` | 전역 5xx 오류 |
| 도메인명 (예: `JWT`, `USER`, `CHAT`) | 도메인 고유 오류 |

**예시**

| 범위 | 에러 코드 예시 |
|---|---|
| 전역 4xx | `CLIENT_ERROR_400_INVALID_REQUEST` |
| 전역 5xx | `SERVER_ERROR_500_INTERNAL_SERVER_ERROR` |
| User 도메인 | `USER_404_NOT_FOUND` |
| JWT | `JWT_401_INVALID` |

### 5.2 규칙

- HTTP 상태 코드 숫자를 애플리케이션 에러 코드로 그대로 사용하지 않습니다.
- 기존 에러 코드는 클라이언트 영향을 확인한 후에만 변경합니다.
- 클라이언트가 새로운 실패 케이스를 구별해야 할 때 새 에러 코드를 추가합니다.
- `message`는 사람이 읽을 수 있는 한국어, `code`는 기계가 읽을 수 있는 영문 상수로 유지합니다.

### 5.3 도메인 에러 코드 추가 방법

새 도메인의 에러 코드는 해당 도메인 패키지 내에 `*ErrorCode` enum을 정의하고 `ErrorCode` 인터페이스를 구현합니다.

```java
// com.blursome.user.code.UserErrorCode
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.", "USER_404_NOT_FOUND");

  private final HttpStatus httpStatus;
  private final String message;
  private final String code;
}
```

Service에서 예외를 발생시킬 때:

```java
throw BaseException.from(UserErrorCode.USER_NOT_FOUND);
```

---

## 6. 구현 규칙

- 성공 응답은 Controller에서 `DataResponse`로 직접 생성합니다.
- 오류 응답은 `GlobalExceptionHandler`에서만 생성합니다 — Controller·Service에서 `ErrorResponse`를 직접 만들지 않습니다.
- Service는 예외를 `BaseException.from(errorCode)` 패턴으로 던집니다.
- 도메인 고유 예외는 해당 도메인의 `*ErrorCode`를 사용합니다.
- `@Valid` 검증 실패 시 `GlobalExceptionHandler`가 자동으로 `reasons` 목록을 포함한 `ErrorResponse`를 반환합니다.
- 처리되지 않은 예외는 `INTERNAL_SERVER_ERROR`로 응답하며, 내부 예외 메시지와 스택 트레이스는 클라이언트에 노출하지 않습니다.
