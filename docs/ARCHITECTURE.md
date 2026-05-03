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

### 회원 (User)

회원 가입, 로그인, 프로필 관리, 계정 생명주기를 담당합니다.

- 핵심 불변 조건: 활성화 상태인 회원만 채팅과 알림을 받을 수 있음
- 주요 도메인 메서드: `deactivate()`, `updateProfile(...)`, `activate()`

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

```
com.blursome
├── user/
│   ├── User.java                      # JPA 엔티티, 풍부한 도메인 모델
│   ├── UserRepository.java            # Spring Data JPA
│   ├── UserService.java               # Facade — 모든 회원 로직 조율
│   ├── UserController.java            # HTTP 어댑터 — UserService에 위임
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── SignUpRequest.java
│   │   └── UserResponse.java
│   └── UserStatus.java                # 회원 상태 Enum
│
├── notification/
│   ├── Notification.java
│   ├── NotificationRepository.java
│   ├── NotificationService.java
│   ├── NotificationController.java
│   └── dto/
│
├── chat/
│   ├── Chat.java
│   ├── ChatRepository.java
│   ├── ChatService.java
│   ├── ChatController.java
│   └── dto/
│
└── common/
    ├── config/                        # Spring 빈, Security, Redis 설정
    ├── exception/
    │   ├── BlurSomeException.java     # 커스텀 예외 베이스
    │   └── GlobalExceptionHandler.java
    └── response/
        └── ApiResponse.java           # 공통 응답 래퍼
```

> 새 파일의 귀속 도메인이 불명확하다면 먼저 도메인 소유권을 검토하세요. `common`은 진정으로 공유되는 인프라만 포함합니다.

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
user.setStatus(UserStatus.INACTIVE);
user.setDeactivatedAt(LocalDateTime.now());
```

**Rich Domain Model (지향)**
```java
// User 엔티티 내부에 비즈니스 규칙 캡슐화
public void deactivate() {
  if (this.status == UserStatus.INACTIVE) {
    throw new BlurSomeException("이미 비활성화된 회원입니다.");
  }
  this.status = UserStatus.INACTIVE;
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
blursome:user:1001:refresh-token
blursome:notification:1001:unread-count
```

---

## 7. 횡단 관심사

| 관심사 | 구현 |
|---|---|
| 인증/인가 | Spring Security + JWT |
| 로깅 | SLF4J + Logback (구조화 로그) |
| 입력 검증 | Jakarta Bean Validation (`@Valid`) — Controller 경계에서 수행 |
| 환경 분리 | `application-{profile}.yml` (`local`, `prod`) — git 추적, 시크릿 없음 |
| 프로파일 활성화 | `application.yml`의 `spring.profiles.default: ${ACTIVE:local}` — `.env`의 `ACTIVE` 변수로 제어 |
| 시크릿 관리 | `.env` 파일 (`spring-dotenv` 로드) — gitignored, `.env.example` 으로 템플릿 공유 |
| API 응답 | `common.response.ApiResponse<T>` 공통 래퍼 |
| 예외 처리 | `common.exception.GlobalExceptionHandler` (`@RestControllerAdvice`) |

---

## 8. 아키텍처 결정 기록 (ADR)

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
