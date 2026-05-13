# 소프트웨어 아키텍처

## 1. 개요

BlurSome은 Spring Boot 3.x 기반의 도메인 주도 REST API 서버입니다. 설계 목표는 **유지보수성**입니다. 각 도메인은 자급자족 패키지로 격리되고, Service 레이어가 Facade로서 모든 비즈니스 오케스트레이션을 담당하며, Controller는 순수 HTTP 어댑터 역할만 수행합니다. 엔티티는 자신의 불변 조건을 스스로 강제하는 풍부한 도메인 모델(Rich Domain Model)을 지향합니다.

### 기술 스택

| 구성 요소 | 기술 |
|---|---|
| 런타임 | Java 21 (GraalVM) |
| 프레임워크 | Spring Boot 3.x |
| 빌드 | Gradle (Kotlin DSL) |
| 주 데이터베이스 | MySQL / MariaDB |
| 캐시 / 세션 | Redis |
| ORM | JPA / Hibernate |
| API 스타일 | RESTful JSON API |
| 환경변수 로딩 | spring-dotenv 4.0.0 |

---

## 2. 도메인 모델

### 인증 (Auth)

OAuth 제공자(현재 Kakao)와의 인증·인가, JWT 발급·검증, 리프레시 토큰 회전, 로그아웃을 담당합니다.

- 핵심 불변 조건: 액세스 토큰은 `typ=access`, 리프레시 토큰은 `typ=refresh` 클레임으로 분리되며, 서로 교차 사용할 수 없음
- 핵심 불변 조건: 회원 1인당 활성 리프레시 토큰은 Redis에 1개만 보관(`blursome:member:<id>:refresh-token`) — 재발급 시 회전
- 주요 컴포넌트: `AuthService`(Facade), `OAuthClientResolver`(공급자 전략 디스패처), `KakaoOAuthClient`(authorization_code → access_token → user info), `JwtTokenProvider`, `RefreshTokenStore`(Redis), `RefreshTokenCookieFactory`(HttpOnly 쿠키)

### 회원 (Member)

회원 가입(OAuth 자동 생성), 프로필 동기화, 계정 상태 조회를 담당합니다.

- 핵심 불변 조건: `(provider, provider_id)` 조합으로 회원을 유일하게 식별 (DB 유니크 제약)
- 핵심 불변 조건: OAuth 신규 회원은 기본 역할 `USER`, 기본 상태 `ACTIVE`로 생성
- 주요 도메인 메서드: `Member.createOAuthMember(...)`(정적 팩토리), `updateProfileFromOAuth(nickname, profileImageUrl)`(변경된 값만 갱신), `isActive()`

### 알림 (Notification)

채팅 메시지, 친구 요청 등 이벤트 기반 알림을 관리합니다.

- 핵심 불변 조건: 알림은 생성 후 수신자만 읽음 처리 가능
- 주요 도메인 메서드: `markAsRead()`

### 채팅 (Chat)

사용자 간 메시지 송수신과 채팅방 관리를 담당합니다.

- 핵심 불변 조건: 채팅방 참가자만 메시지를 전송/조회 가능
- 주요 도메인 메서드: `addParticipant(...)`, `sendMessage(...)`

---

## 3. 패키지 구조

각 도메인 패키지는 레이어 서브패키지로 책임을 분리합니다. `domain/`은 JPA 엔티티와 해당 도메인의 Enum·값객체를 함께 담습니다.

```
com.blursome
├── auth/
│   ├── controller/
│   │   └── AuthController.java        # /api/auth/oauth/kakao, /api/auth/token/refresh, /api/auth/logout
│   ├── service/
│   │   └── AuthService.java           # Facade — OAuth 로그인, 토큰 회전, 로그아웃 조율
│   ├── oauth/
│   │   ├── OAuthClient.java           # OAuth 공급자 추상화 (provider, fetchUserInfo)
│   │   ├── OAuthClientResolver.java   # 공급자 → OAuthClient 디스패처
│   │   └── kakao/
│   │       ├── KakaoOAuthClient.java        # Kakao 토큰 교환 + 사용자 조회
│   │       ├── KakaoOAuthProperties.java    # app.oauth.kakao.* 설정 바인딩
│   │       ├── KakaoTokenResponse.java      # Kakao 토큰 응답 DTO
│   │       └── KakaoUserInfoResponse.java   # Kakao 사용자 정보 응답 DTO
│   ├── token/
│   │   └── RefreshTokenStore.java     # Redis 기반 리프레시 토큰 저장소
│   ├── cookie/
│   │   ├── CookieProperties.java      # app.cookie.* 설정 바인딩
│   │   └── RefreshTokenCookieFactory.java   # HttpOnly Set-Cookie 생성/삭제
│   ├── dto/
│   │   ├── TokenPair.java             # 액세스+리프레시+TTL 묶음 (서비스 내부 전송)
│   │   ├── request/
│   │   │   └── KakaoLoginRequest.java
│   │   └── response/
│   │       └── AuthTokenResponse.java # 클라이언트에 노출되는 액세스 토큰 응답
│   └── exception/
│       └── AuthErrorCode.java         # OAuth/Refresh 토큰 관련 에러 코드
│
├── member/
│   ├── service/
│   │   └── MemberService.java         # Facade — 회원 조회/생성/프로필 동기화
│   ├── repository/
│   │   └── MemberRepository.java      # Spring Data JPA
│   ├── domain/
│   │   ├── Member.java                # JPA 엔티티, 풍부한 도메인 모델
│   │   ├── MemberRole.java            # 회원 권한 Enum (USER, ADMIN)
│   │   ├── MemberStatus.java          # 회원 상태 Enum (ACTIVE, INACTIVE)
│   │   └── OAuthProvider.java         # OAuth 공급자 Enum (KAKAO)
│   ├── dto/
│   │   └── OAuthUserInfo.java         # OAuth 공급자 응답을 도메인으로 변환한 DTO
│   └── exception/
│       └── MemberErrorCode.java       # ErrorCode 구현 Enum
│
├── notification/                      # (예정)
│   └── ...
│
├── chat/                              # (예정)
│   └── ...
│
└── global/
    ├── security/
    │   ├── SecurityConfig.java               # Spring Security 필터 체인 (Stateless)
    │   ├── JwtProperties.java                # app.jwt.* 설정 바인딩
    │   ├── JwtTokenProvider.java             # JWT 발급/파싱 (access/refresh typ 구분)
    │   ├── JwtAuthentication.java            # Authentication 구현 (principal = memberId)
    │   ├── JwtAuthenticationFilter.java      # Authorization Bearer 추출 → SecurityContext 등록
    │   └── JwtAuthenticationEntryPoint.java  # 인증 실패 시 ErrorResponse JSON 직렬화
    ├── persistence/
    │   ├── BaseEntity.java                   # @MappedSuperclass — createdAt/updatedAt 감사 필드
    │   └── JpaAuditingConfig.java            # @EnableJpaAuditing
    ├── exception/
    │   ├── BaseException.java                # 커스텀 예외 베이스 (BaseException.from(errorCode))
    │   ├── GlobalExceptionHandler.java
    │   ├── JwtAuthenticationException.java   # Spring Security AuthenticationException 확장
    │   └── code/
    │       ├── ErrorCode.java                # 에러 코드 인터페이스
    │       ├── GlobalErrorCode.java          # 전역 공통 에러 코드
    │       └── JwtErrorCode.java             # JWT 에러 코드
    └── response/
        ├── BaseResponse.java                 # 공통 메타데이터 (timestamp)
        ├── DataResponse.java                 # 성공 응답 래퍼
        ├── ErrorResponse.java                # 오류 응답 래퍼
        └── ErrorDetail.java                  # 검증 실패 필드 상세
```

### 레이어 서브패키지 역할 요약

| 서브패키지 | 포함 대상 | 규칙 |
|---|---|---|
| `controller/` | `*Controller` | HTTP 변환만, Repository 직접 주입 금지 |
| `service/` | `*Service` | 트랜잭션 경계, 도메인 로직 조율, DTO 변환 |
| `repository/` | `*Repository` | 데이터 접근만, 비즈니스 로직 없음 |
| `domain/` | Entity, Enum, 값객체 | JPA 엔티티 + 해당 도메인의 타입 정의 |
| `dto/request/` | `*Request` | Controller 입력 DTO, `@Valid` 검증 어노테이션 |
| `dto/response/` | `*Response` | Service → Controller 출력 DTO |
| `exception/` | `*ErrorCode` | `ErrorCode` 인터페이스 구현 Enum |

> 새 파일의 귀속 도메인이 불명확하다면 먼저 도메인 소유권을 검토하세요. `global`은 횡단 관심사 인프라만 포함합니다.

네이밍 규칙은 [`docs/CODE_CONVENTION.md § 네이밍 컨벤션`](CODE_CONVENTION.md#2-네이밍-컨벤션) 참고.

---

## 4. Facade 패턴

### 개념

Service 클래스가 Facade입니다. JPA 쿼리, Redis 캐싱, 도메인 간 조율, 비즈니스 규칙의 복잡함을 Controller로부터 숨깁니다. Controller의 유일한 역할은 HTTP ↔ 도메인 호출 변환입니다.

### 의존 방향

```
HTTP 요청
    └── Controller          (요청 DTO 수신 → Service 호출 → 응답 DTO 반환)
          └── Service        (Facade — Repository, 도메인 로직, 타 Service 조율)
                ├── Repository      (데이터 접근만)
                └── Domain Entity   (비즈니스 규칙은 엔티티에 존재)
```

도메인 간 조율은 **Service → Service** 경로만 허용됩니다. Repository → Repository 직접 접근은 금지입니다.

### Service가 하는 일

- 트랜잭션 경계 관리 (`@Transactional`)
- Repository를 통한 데이터 조회/저장
- 도메인 간 조율 (예: 채팅 메시지 전송 시 알림 생성)
- Entity → Response DTO 변환

### Service가 하지 않는 일

- HTTP 관심사 (상태 코드, 헤더) — Controller 책임
- 원시 SQL / JDBC — Repository 책임
- 비즈니스 불변 조건 강제 — Domain Entity 책임

### Controller가 하지 않는 일

- `*Repository` 직접 주입 또는 호출
- 비즈니스 if/else 로직
- `@Transactional` 사용

---

## 5. 풍부한 도메인 모델 (Rich Domain Model)

엔티티는 데이터 저장 가방이 아닙니다. 자신의 불변 조건을 강제하는 메서드를 가집니다.

**Anemic Model (지양)**
```java
// Service에서 직접 setter 호출 — 비즈니스 의미 없음
member.setStatus(MemberStatus.INACTIVE);
member.setDeactivatedAt(LocalDateTime.now());
```

**Rich Domain Model (지향)**
```java
// Member 엔티티 내부에 비즈니스 규칙 캡슐화
public void deactivate() {
  if (this.status == MemberStatus.INACTIVE) {
    throw BaseException.from(MemberErrorCode.ALREADY_DEACTIVATED);
  }
  this.status = MemberStatus.INACTIVE;
  this.deactivatedAt = LocalDateTime.now();
}
```

엔티티 외부에서 필드를 직접 조작하는 setter를 노출하지 않습니다.

---

## 6. 데이터 레이어

### JPA

- Repository는 `JpaRepository<Entity, Long>` 상속
- 단순 쿼리: Spring Data 메서드 네이밍 (`findByEmail`, `findAllByStatus`)
- 복잡한 쿼리: JPQL `@Query` 어노테이션 사용
- 성능 근거가 문서화된 경우에만 QueryDSL 또는 native query 허용
- 성능 근거 없는 raw JDBC 사용 금지

### Redis

주요 사용 목적:
- JWT 리프레시 토큰 저장
- 알림 미읽음 카운트 캐싱
- 고빈도 조회 데이터 캐싱

키 네이밍 스킴:
```
blursome:<domain>:<id>:<field>

예시:
blursome:member:1001:refresh-token
blursome:notification:1001:unread-count
```

---

## 7. 횡단 관심사

| 관심사 | 구현 |
|---|---|
| 인증/인가 | Spring Security (Stateless) + JWT(HS256, `typ` 클레임으로 access/refresh 구분), OAuth 2.0 인가 코드 그랜트 (현재 Kakao), 리프레시 토큰은 Redis 저장(`blursome:member:<id>:refresh-token`) + HttpOnly 쿠키 (`refreshToken`, path `/api/auth`) — 자세한 흐름은 § 9 참고 |
| 로깅 | SLF4J + Logback (구조화 로그) |
| 입력 검증 | Jakarta Bean Validation (`@Valid`) — Controller 경계에서 수행 |
| 환경 분리 | `application-{profile}.yml` (`local`, `prod`) — git 추적, 시크릿 없음 |
| 프로파일 활성화 | `application.yml`의 `spring.profiles.default: ${ACTIVE:local}` — `.env`의 `ACTIVE` 변수로 제어 |
| 시크릿 관리 | `.env` 파일 (`spring-dotenv` 로드) — gitignored, `.env.example` 으로 템플릿 공유 |
| API 성공 응답 | `global.response.DataResponse<T>` — `DataResponse.ok(data)`, HTTP 상태 코드는 `ResponseEntity`로 전달 |
| API 오류 응답 | `global.response.ErrorResponse` — `GlobalExceptionHandler`가 자동 생성 |
| 예외 처리 | `global.exception.GlobalExceptionHandler` (`@RestControllerAdvice`) |

---

## 8. 인증/인가 흐름

### 토큰 모델

| 항목 | Access Token | Refresh Token |
|---|---|---|
| 전달 채널 | `Authorization: Bearer <token>` 헤더 | `refreshToken` HttpOnly 쿠키 (path `/api/auth`) |
| 발급 클레임 | `sub=memberId`, `role`, `typ=access` | `sub=memberId`, `typ=refresh` |
| 서명 알고리즘 | HMAC-SHA256 (`app.jwt.secret`, 최소 32바이트) |
| 기본 만료 | `app.jwt.access-token-expires-in` (기본 1800초) | `app.jwt.refresh-token-expires-in` (기본 1209600초) |
| 서버 측 저장 | 없음 (Stateless) | Redis `blursome:member:<id>:refresh-token` (값 = JWT) |
| 회전 정책 | — | 갱신 시마다 새 토큰 발급 후 동일 키에 덮어쓰기, 불일치 시 저장소 삭제 후 401 |

### 엔드포인트

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/oauth/kakao` | 공개 | 카카오 인가 코드(`code`)로 로그인. 응답 바디에 액세스 토큰, `Set-Cookie`로 리프레시 토큰 |
| POST | `/api/auth/token/refresh` | 공개 (쿠키 기반) | 쿠키의 리프레시 토큰을 검증·회전, 새 토큰 쌍 반환 |
| POST | `/api/auth/logout` | 필요 (`Bearer`) | Redis 리프레시 토큰 삭제 + 쿠키 만료(`max-age=0`) |

### 카카오 로그인 시퀀스

```
[Client] ── POST /api/auth/oauth/kakao { code } ──▶ AuthController
                                                       │
                                                       ▼
                                                   AuthService.loginWithKakao
                                                       │
                            ┌──────────────────────────┼──────────────────────────────┐
                            ▼                          ▼                              ▼
                   OAuthClientResolver         KakaoOAuthClient                MemberService
                   .resolve(KAKAO)             .fetchUserInfo(code)            .findOrCreateByOAuth
                                                  ├─ POST kauth/oauth/token      ├─ findByProviderAndProviderId
                                                  └─ GET  kapi/v2/user/me        └─ save(...) 또는 updateProfileFromOAuth(...)
                                                       │
                                                       ▼
                                                JwtTokenProvider.issueAccessToken/Refresh
                                                       │
                                                       ▼
                                                RefreshTokenStore.save(memberId, refresh, ttl)
                                                       │
                                                       ▼
[Client] ◀── 200 OK + DataResponse<AuthTokenResponse> + Set-Cookie: refreshToken=... ──
```

### 리프레시 토큰 회전

1. 클라이언트가 `/api/auth/token/refresh`를 POST → 쿠키의 `refreshToken`이 자동 첨부
2. `JwtTokenProvider.parseRefresh`로 `typ=refresh` 검증 및 `memberId` 추출
3. Redis에 저장된 토큰과 비교
   - 키가 없음 → `AUTH_401_REFRESH_TOKEN_NOT_FOUND`
   - 값이 다름 → 저장소를 비우고 `AUTH_401_REFRESH_TOKEN_MISMATCH` (재사용 공격 대응)
4. `MemberService.findActiveMember`로 활성 회원인지 확인
5. 새 액세스/리프레시 토큰 발급, Redis 동일 키에 덮어쓰기 → 응답에 새 쿠키 동봉

### 로그아웃

- 인증 필터를 통과한 `memberId`(`@AuthenticationPrincipal Long`)로 Redis 리프레시 토큰 삭제
- 만료 처리된 동일 속성의 `Set-Cookie`(value 공백, `max-age=0`)로 클라이언트 쿠키 제거
- 액세스 토큰 자체는 별도 블랙리스트 없이 만료될 때까지 유효 (만료 주기가 짧다는 전제)

### 인증 실패 응답

`JwtAuthenticationFilter`가 토큰을 검증하다 `JwtAuthenticationException`을 던지면 `JwtAuthenticationEntryPoint`가 `ErrorResponse`를 JSON으로 직접 직렬화한다(`@RestControllerAdvice` 대신 `AuthenticationEntryPoint` 경로). 코드 매핑은 `JwtErrorCode` 참고.

| 상황 | JwtErrorCode | HTTP |
|---|---|---|
| 서명/형식 오류, `typ` 불일치 | `INVALID_TOKEN` | 401 |
| 만료된 토큰 | `EXPIRED_TOKEN` | 401 |
| 인증이 필요한 자원에 토큰 없이 접근 | `UNAUTHORIZED` | 401 |

### 운영 시 유의 사항

- **쿠키 SameSite/Secure**: 로컬은 `SameSite=Lax`, `Secure=false`. 프로덕션은 `Secure=true`이지만 SPA가 다른 오리진에서 동작한다면 `SameSite`를 `None`으로 조정해야 POST 시 쿠키가 동봉된다. 설정값: `app.cookie.secure`, `app.cookie.same-site`.
- **단일 활성 세션**: 리프레시 토큰 키가 `memberId` 단일이므로, 새 기기 로그인은 이전 기기의 리프레시 토큰을 무효화한다. 멀티 디바이스 동시 세션이 필요할 경우 키에 디바이스 ID/JTI 차원을 추가해야 한다.
- **JWT 시크릿**: HS256은 최소 256bit 키를 요구한다. `.env.example`에 명시된 32바이트 이상 임의 문자열을 사용한다.

---

## 9. 아키텍처 결정 기록 (ADR)

중요한 아키텍처 결정이 내려질 때 이 섹션에 기록합니다.

```
형식:
## ADR-001: <제목>
- 날짜: YYYY-MM-DD
- 상태: 승인됨 / 논의 중 / 폐기됨
- 결정: <무엇을 결정했는가>
- 이유: <왜 이 결정을 했는가>
- 결과: <이 결정이 미치는 영향>
```

### ADR-001: .env 파일 기반 시크릿 관리
- 날짜: 2026-05-03
- 상태: 승인됨
- 결정: `application-local.yml`, `application-prod.yml` 을 git으로 추적하되, 모든 민감값(DB 접속 정보, Redis 비밀번호 등)은 환경변수로 외부화한다. 로컬 개발 시에는 `spring-dotenv` 라이브러리가 프로젝트 루트의 `.env` 파일을 자동으로 읽어 주입한다.
- 이유: profile yml 파일을 팀 공유 자산으로 git 추적하면서도, 시크릿이 저장소에 노출되지 않도록 분리가 필요했다. `.env` 방식은 Docker/CI 파이프라인과도 동일한 패턴으로 운영 환경 전환이 쉽다.
- 결과:
  - yml 파일은 환경변수 참조(`${VAR}`)만 포함 — 시크릿 없음
  - `.env` 는 gitignore, `.env.example` 은 git 추적 (온보딩 템플릿)
  - `spring-dotenv:4.0.0` 의존성 추가 (`build.gradle.kts`)
  - `ACTIVE` 변수로 활성 프로파일 지정 (`ACTIVE=local` 또는 `ACTIVE=prod`), 미지정 시 `local` 폴백
  - 프로덕션 서버는 `.env` 대신 서버 환경변수 또는 시크릿 매니저에서 동일 변수명으로 주입
