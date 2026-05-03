# CLAUDE.md — BlurSome 오케스트레이션 문서

Claude Code가 이 프로젝트에서 작업할 때 반드시 이 문서를 참조합니다.

---

## 1. 프로젝트 식별

| 항목 | 내용 |
|---|---|
| 프로젝트 | BlurSome |
| 타입 | Spring Boot 3.x REST API 서버 |
| 언어 / JVM | Java 21, GraalVM |
| 빌드 | Gradle (Kotlin DSL) |
| 패키지 루트 | `com.blursome` |
| 주요 도메인 | 회원(User), 알림(Notification), 채팅(Chat) |

---

## 2. 문서 라우팅 맵

작업 전 아래 표에서 참조 문서를 확인합니다.

| 작업 | 참조 문서 및 섹션 |
|---|---|
| 커밋 메시지 작성/리뷰 | `docs/GIT_CONVENTION.md § 2. 커밋 메시지 컨벤션` |
| 브랜치 생성/네이밍 | `docs/GIT_CONVENTION.md § 1. 브랜치 전략` |
| PR 작성/리뷰 | `docs/GIT_CONVENTION.md § 3. PR 컨벤션` |
| Java 코드 작성/리뷰 전반 | `docs/CODE_CONVENTION.md` |
| 클래스/메서드/패키지 네이밍 | `docs/CODE_CONVENTION.md § 2. 네이밍 컨벤션` |
| 새 도메인/기능 추가 | `docs/ARCHITECTURE.md § 3. 패키지 구조` + `§ 4. Facade 패턴` |
| 로직 위치 결정 (어디에 코드를 둘지) | `docs/ARCHITECTURE.md § 4. Facade 패턴` |
| 예외 클래스 추가 | `docs/CODE_CONVENTION.md § 6. 예외 처리` |
| API 엔드포인트 추가 | `docs/CODE_CONVENTION.md § 5. API 응답 컨벤션` |
| 테스트 작성 | `docs/CODE_CONVENTION.md § 8. 테스트 컨벤션` |
| 아키텍처 결정 기록 | `docs/ARCHITECTURE.md § 8. ADR` |

---

## 3. 상시 적용 규칙

사용자가 별도로 요청하지 않아도 모든 작업에 자동 적용합니다.

1. **커밋 메시지와 PR 제목은 한국어로 작성** (`타입: 한글 제목`)
2. **브랜치 네이밍**: `feature/<이슈번호>-<한글-설명>` 형식 준수
3. **Controller에서 Repository 직접 주입/호출 금지** — 반드시 Service 경유
4. **Controller에 비즈니스 로직 금지** — HTTP 변환만 허용
5. **`@Transactional`은 Service 메서드에만** — Controller 사용 금지
6. **모든 API 응답은 `ApiResponse<T>` 래퍼 사용** (`common.response.ApiResponse`)
7. **JPA Entity에 `@Data` 금지** — `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 사용
8. **Entity에 public 기본 생성자 금지** — `PROTECTED` 접근 수준 강제
9. **도메인에 귀속되는 클래스는 해당 도메인 패키지에** — `common`은 공유 인프라만
10. **의존성 주입은 생성자 주입** (`@RequiredArgsConstructor`) — 필드 `@Autowired` 금지

---

## 4. 아키텍처 빠른 참조

```
HTTP 요청
    └── *Controller          (요청 DTO 수신, @Valid 검증, Service 호출, 응답 반환)
          └── *Service        (Facade: 트랜잭션, 쿼리 조합, 도메인 간 조율, DTO 변환)
                ├── *Repository      (데이터 접근만)
                └── Domain Entity    (비즈니스 불변 조건은 엔티티 메서드에)
```

도메인 간 조율: **Service → Service** 경로만 허용 (Repository → Repository 직접 접근 금지)

---

## 5. 새 기능 추가 체크리스트

새 기능 구현 요청 시 아래 순서로 진행합니다.

1. **도메인 소유권 확인** — User / Notification / Chat / 신규 도메인 중 어디에 속하는지 결정
2. **Entity 생성**: `com.blursome.<domain>/<DomainName>.java`
   - `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
   - 상태 변경은 의미 있는 도메인 메서드로 캡슐화
3. **Repository 생성**: `com.blursome.<domain>/<DomainName>Repository.java`
4. **Service(Facade) 생성**: `com.blursome.<domain>/<DomainName>Service.java`
   - `@Transactional` 적용
   - Entity → Response DTO 변환 담당
5. **Controller 생성**: `com.blursome.<domain>/<DomainName>Controller.java`
   - `@Valid` 검증
   - `ApiResponse<T>` 반환
6. **공유 관심사**는 `com.blursome.common.*`에 배치
7. 작업 완료 후 **한국어 커밋 메시지 제안**

---

## 6. 기술 스택 빠른 참조

```
Spring Boot 3.x  │  Java 21  │  Gradle Kotlin DSL
MySQL/MariaDB    — 주 영속성 (JPA/Hibernate)
Redis            — 캐시, 세션, 토큰, 고빈도 조회
GraalVM JDK 21   — .idea/misc.xml 구성됨
Google Java Style Guide — Checkstyle (.idea/checkstyle-idea.xml)
```
