# 회원 온보딩 설계 문서

소셜 로그인으로 진입한 사용자가 **학교 이메일 인증 → 프로필 작성(온보딩)** 을 거쳐 정식 회원이 되는 온보딩 플로우를 정의합니다. 가입 단계(`RegistrationStatus`) 전이 규칙은 [`docs/member/MEMBER_DOMAIN.md`](./MEMBER_DOMAIN.md)를 따르며, 본 문서는 그 위에서 **인증 코드 발송/검증 메커니즘**과 **온보딩 입력 항목(관심사 포함)** 을 구체화합니다.

> 상태 표기
> - ✅ **확정**: 본 문서/코드에 반영됨
> - 🧩 **설계(제안)**: 방향은 정했으나 일부 후속 보강 예정
> - ⏳ **미정(TODO)**: 결정/구현이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따릅니다.

---

## 1. 개요

### 목표

- 카카오 로그인 직후의 회원(`UNVERIFIED`)을 **학교 이메일 인증**으로 `VERIFIED`로 전진시킨다.
- 이메일 인증은 **6자리 코드 발송 → 사용자 입력 코드 검증** 2단계로 한다.
- 인정 도메인은 **안양대학교(`@gs.anyang.ac.kr`)** 로 제한한다(도메인 파싱 검증).
- 인증 이후 **닉네임·생년·학과·MBTI·성별·관심사**를 입력받아 저장하고 `COMPLETED`로 전진시킨다.
- 관심사는 별도 엔티티(`interest_category`)로 분리해 한 회원이 다중 선택할 수 있게 한다.

### 핵심 결정 (✅ 확정)

| 항목 | 결정 | 비고 |
|---|---|---|
| 인증 코드 저장 | **Redis + TTL(5분)** | 기존 `RefreshTokenStore`와 동일 패턴. 만료는 Redis가 자동 처리 |
| 인증 코드 | **6자리 숫자**, `SecureRandom`, 앞자리 0 보존 | `000000`~`999999` |
| 인정 도메인 | **`@gs.anyang.ac.kr` 단일 화이트리스트** | 도메인부 대소문자 무시. 화이트리스트 확장 가능 구조 |
| 이메일 발송 | **포트 추상화(`EmailVerificationSender`) + 로깅 스텁** | 실제 SMTP 구현은 후속(TODO). 의존성/설정 없이 플로우 검증 가능 |
| 관심사 `name` | **고정 enum `InterestCategoryType` + STRING 저장** | 스키마 `INT` 코멘트 대신 프로젝트 컨벤션(`@Enumerated(STRING)`) 채택 |
| 관심사 모델 | **`interest_category` 별도 테이블**, `(member_id, name)` 유니크 | 회원당 같은 관심사 중복 방지 |

### 전제 / 선행

- 소셜 로그인·JWT 인증은 기존 체계 사용. 온보딩 API는 **로그인된 회원**만 접근(JWT의 `memberId` 사용, `@AuthenticationPrincipal`).
- 가입 단계 전이의 불변식(`UNVERIFIED → VERIFIED → COMPLETED`, 단조 전진)은 `Member` 도메인 메서드에 캡슐화되어 있다([`MEMBER_DOMAIN.md` §3·§4](./MEMBER_DOMAIN.md)).

---

## 2. 도메인 모델 변경/추가

### 2-1. Member / Feed 역할 분리 (온보딩 입력 필드)

온보딩 입력값은 두 엔티티로 나눠 저장한다. `Member`는 가입 정체성(`nickName`)만 받고, **공개 프로필(생년·학과·MBTI·성별)은 `Feed`가 단독 보유**한다. `Member`에는 이 4개 필드를 두지 않는다(중복 제거).

| 입력 필드 | 저장 위치 | 비고 |
|---|---|---|
| `nickName` | `Member.nickName` (+ `Feed.nickName` 복사) | 가입 정체성. 유니크. 피드에 비정규화 중복 |
| `birthYear` | `Feed.birthYear` | `nullable=false`(피드는 온보딩 후에만 생성) |
| `department` | `Feed.department` (`Department` enum, STRING) | `nullable=false`, len 50. 학과 정규화(이슈 #40) — 고정 enum 입력 |
| `mbti` | `Feed.mbti` (`Mbti` enum, STRING) | **`nullable=true`**, len 4. **선택값**: 미입력(null)="모름"(#76) |
| `gender` | `Feed.gender` (`Gender` = MALE/FEMALE) | `nullable=false`, len 10 |

> `completeOnboarding`의 시그니처는 `(nickName)`으로 축소됐고, 4개 공개 프로필 필드는 `FeedService.createFeed(member, gender, birthYear, department, mbti)`로 전달된다. 피드 상세는 [`docs/feed/FEED_DOMAIN.md`](../feed/FEED_DOMAIN.md).

### 2-2. InterestCategory (`interest_category`) — 🆕

회원이 선택한 관심사 1건. 한 회원이 여러 관심사를 가지므로 `member_id`로 회원을 참조하는 별도 행으로 저장한다.

| 필드 | 타입 / 제약 | 출처 / 설명 |
|---|---|---|
| `id` | PK, IDENTITY | |
| `name` | `InterestCategoryType`(enum, **STRING**), `nullable=false`, len 30 | 관심사 카탈로그(농구·독서 등) |
| `description` | `String`, nullable, len 200 | 선택 시점 카탈로그 설명을 비정규화 저장 |
| `member` | `Member` `@ManyToOne(LAZY)`, `member_id`, `nullable=false` | FK → `member.id` |
| `createdAt`/`updatedAt` | `BaseEntity` | 감사 필드(컨벤션) |

#### 제약 조건

| 제약 | 정의 | 목적 |
|---|---|---|
| `uk_interest_category_member` | `(member_id, name)` 유니크 | 회원당 동일 관심사 중복 선택 방지 |

#### 원본 DDL과의 차이 (✅ 의도된 조정)

```sql
-- 과제 제공 DDL
CREATE TABLE `interest_category` (
  `id` BIGINT NOT NULL,
  `name` INT NOT NULL COMMENT 'ex) 농구, 독서 등',
  `description` VARCHAR(200) NULL,
  `member_id` BIGINT NOT NULL COMMENT 'FK → member.id'
);
```

| 항목 | 원본 DDL | 구현 | 이유 |
|---|---|---|---|
| `name` 타입 | `INT` | `VARCHAR`(enum STRING) | 프로젝트는 enum을 `@Enumerated(STRING)`으로 저장(ordinal 취약성 회피, 가독성) |
| 감사 컬럼 | 없음 | `created_at`/`updated_at` 추가 | `BaseEntity` 컨벤션 |
| 유니크 | 없음 | `(member_id, name)` | 중복 선택 방지 |

### 2-3. Enum 추가

| Enum | 값 | 비고 |
|---|---|---|
| `Mbti` | `ISTJ … ENTJ`(16) | STRING 저장 |
| `InterestCategoryType` | `BASKETBALL("농구"), READING("독서"), …` | 한글 라벨 + 기본 설명 보유. **STRING 저장이므로 값 이름 변경 금지, append 만 허용** |

---

## 3. 온보딩 플로우

```
[카카오 로그인 완료 → JWT 보유, registrationStatus = UNVERIFIED]
        │
        ▼
 (1) POST /api/members/me/school-email/verification-codes  { schoolEmail }
        ├─ 활성 회원·미인증 확인, 도메인(@gs.anyang.ac.kr) 검증
        ├─ 6자리 코드 생성(SecureRandom)
        ├─ Redis 저장: key=blursome:member:<id>:school-email-verification, value="<email>|<code>", TTL 5분
        └─ EmailVerificationSender.send(email, code)   (현재 로깅 스텁)
        │
        ▼
 (2) POST /api/members/me/school-email/verifications  { schoolEmail, code }
        ├─ Redis 조회(없으면 404 CODE_NOT_FOUND/만료)
        ├─ 저장된 email·code 와 요청 일치 검증(불일치 400 MISMATCH)
        ├─ member.verifySchoolEmail(schoolEmail)        (UNVERIFIED → VERIFIED)
        │     └─ school_email 유니크 충돌 → 409 SCHOOL_EMAIL_DUPLICATED
        └─ Redis 코드 삭제(1회용)
        │
        ▼
 (3) POST /api/members/me/onboarding  { nickName, birthYear, department, mbti, gender, interests[] }
        ├─ member.completeOnboarding(nickName)   (VERIFIED → COMPLETED, 닉네임만 세팅)
        │     ├─ VERIFIED 아니면 409 SCHOOL_VERIFICATION_REQUIRED / 이미 완료면 409 ALREADY_ONBOARDED
        │     └─ nick_name 유니크 충돌 → 409 NICKNAME_DUPLICATED
        ├─ interests 저장(InterestCategory, distinct)
        └─ Feed 생성(FeedService.createFeed(member, gender, birthYear, department, mbti))
              — 공개 프로필을 Feed에 저장, 온보딩 트랜잭션과 원자적 처리
        │
        ▼
 [registrationStatus = COMPLETED → canUseService() = true, Feed 행 생성 완료]
```

- (1)은 DB를 변경하지 않으므로 읽기 트랜잭션, (2)·(3)은 쓰기 트랜잭션.
- 유니크 충돌(학교 이메일/닉네임)은 `flush()` 시점에 `DataIntegrityViolationException`을 잡아 도메인 예외로 변환한다(기존 `MemberService.createMember` 패턴과 동일).

---

## 4. API

| 메서드 | 경로 | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| `GET` | `/api/members/me` | — | `200 DataResponse<MemberProfileResponse>` | 내 프로필 조회 |
| `POST` | `/api/members/me/school-email/verification-codes` | `SendSchoolEmailCodeRequest` | `204 No Content` | 인증 코드 발송 |
| `POST` | `/api/members/me/school-email/verifications` | `VerifySchoolEmailRequest` | `204 No Content` | 코드 검증·학교 인증 |
| `POST` | `/api/members/me/onboarding` | `OnboardingRequest` | `200 DataResponse<MemberProfileResponse>` | 온보딩 완료 |

모든 엔드포인트는 인증 필요(`SecurityConfig`의 `anyRequest().authenticated()`로 이미 보호됨 — 별도 permitAll 미추가).

### 요청 DTO 검증

- `SendSchoolEmailCodeRequest`: `schoolEmail` `@NotBlank @Email`
- `VerifySchoolEmailRequest`: `schoolEmail` `@NotBlank @Email`, `code` `@Pattern(\d{6})`
- `OnboardingRequest`: `nickName` `@NotBlank @Size(max=30)`, `birthYear` `@NotNull @Min(1900) @Max(2100)`, `department` `@NotNull`(`Department` enum), `mbti` **선택값(검증 없음, null="모름" 허용, #76)**, `gender` `@NotNull`, `interests` `@NotEmpty`

---

## 5. 패키지 구조

```
com.blursome.member
├── controller/
│   └── MemberController.java                 # 🆕 온보딩/내정보 API
├── service/
│   ├── MemberService.java                    # 기존 (findActiveMember 재사용)
│   └── MemberOnboardingService.java          # 🆕 온보딩 Facade
├── repository/
│   ├── MemberRepository.java                 # 기존 (flush로 유니크 검증)
│   └── InterestCategoryRepository.java       # 🆕
├── domain/
│   ├── Member.java                           # 공개 프로필 4필드는 Feed로 이관, completeOnboarding(nickName)으로 축소
│   ├── Mbti.java                             # 🆕
│   ├── InterestCategory.java                 # 🆕
│   └── InterestCategoryType.java            # 🆕
├── dto/
│   ├── request/
│   │   ├── SendSchoolEmailCodeRequest.java   # 🆕
│   │   ├── VerifySchoolEmailRequest.java     # 🆕
│   │   └── OnboardingRequest.java            # 🆕
│   └── response/
│       ├── MemberProfileResponse.java        # 🆕
│       └── InterestResponse.java             # 🆕
├── verification/                             # 🆕 이메일 인증 인프라
│   ├── EmailVerificationSender.java          # 포트
│   ├── LoggingEmailVerificationSender.java   # 로깅 스텁(기본)
│   ├── VerificationCodeGenerator.java        # 6자리 코드
│   ├── SchoolEmailPolicy.java                # 도메인 화이트리스트
│   └── SchoolEmailVerificationStore.java     # Redis 저장소
└── exception/
    └── MemberErrorCode.java                  # 코드 추가
```

> `verification/`은 회원 도메인에 귀속되는 온보딩 전용 인프라이므로 `member` 하위에 둔다(`global`은 횡단 관심사 전용 — 컨벤션 §9).

---

## 6. 에러 코드 (추가분)

`com.blursome.member.exception.MemberErrorCode`

| 코드 | 상황 | HTTP |
|---|---|---|
| `MEMBER_400_INVALID_SCHOOL_EMAIL_DOMAIN` | 인정 도메인(@gs.anyang.ac.kr) 아님 | 400 |
| `MEMBER_404_VERIFICATION_CODE_NOT_FOUND` | 코드 미발송/만료 | 404 |
| `MEMBER_400_VERIFICATION_CODE_MISMATCH` | 이메일·코드 불일치 | 400 |

재사용(기존): `MEMBER_409_ALREADY_VERIFIED`, `MEMBER_409_SCHOOL_EMAIL_DUPLICATED`, `MEMBER_409_SCHOOL_VERIFICATION_REQUIRED`, `MEMBER_409_ALREADY_ONBOARDED`, `MEMBER_409_NICKNAME_DUPLICATED`, `MEMBER_403_INACTIVE`, `MEMBER_404_NOT_FOUND`.

---

## 7. 보안·운영 고려

- **코드 추측 방어**: 6자리(100만 경우의 수) + 5분 TTL. (⏳ 후속) 시도 횟수 제한(N회 초과 시 폐기), 발송 레이트리밋.
- **이메일 1회용**: 검증 성공 시 즉시 Redis 키 삭제.
- **메일 발송**: 현재 `LoggingEmailVerificationSender`가 코드를 로그로만 출력(`[EMAIL-STUB] WARN`). **운영 배포 전 반드시 SMTP 구현으로 대체**해야 함.

---

## 8. 남은 항목 (⏳ TODO)

- **실제 SMTP 구현** — `spring-boot-starter-mail` 도입 + `SmtpEmailVerificationSender`, 로깅 스텁은 `local` 프로파일로 한정.
- **인증 시도 제한 / 발송 레이트리밋** — 무차별 코드 입력·메일 폭탄 방어.
- **인정 도메인 확장** — 대학원/타 캠퍼스 도메인 추가 시 `SchoolEmailPolicy` 화이트리스트를 집합으로 확장.
- **관심사 수정/재선택 API** — 온보딩 이후 관심사 변경(현재 `InterestCategoryRepository.deleteByMemberId` 보유, 노출 API 미정).
- **피드 탐색 API** — 관심사 기반 피드 조회 구현. 설계는 [`docs/feed/FEED_DOMAIN.md`](../feed/FEED_DOMAIN.md) 참조.
- **닉네임 변경 동기화** — 닉네임 변경 기능 도입 시 `Feed.updateNickName()` 연동 필요.

---

## 부록: 관련 코드 위치

- 엔티티: `src/main/java/com/blursome/blursome/member/domain/`
- 온보딩 서비스: `.../member/service/MemberOnboardingService.java`
- 인증 인프라: `.../member/verification/`
- 가입 단계 도메인 규칙: [`docs/member/MEMBER_DOMAIN.md`](./MEMBER_DOMAIN.md)
