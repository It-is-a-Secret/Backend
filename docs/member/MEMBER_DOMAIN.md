# 회원 도메인 설계 문서

BlurSome의 회원(Member) 도메인을 정의합니다. 소셜 로그인으로 진입한 사용자가 **학교 이메일 인증 → 온보딩 작성**을 거쳐 정식 회원이 되는 가입 단계와, 탈퇴를 포함한 활동 상태를 Rich Domain 관점에서 모델링합니다.

> 상태 표기
> - ✅ **확정**: 의사결정으로 본 문서/스키마에 반영됨
> - 🧩 **설계(제안)**: 방향은 정했으나 구현 전 — 검토 후 조정 가능
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따릅니다.

---

## 1. 개요

### 목표

- 소셜 로그인(현재 Kakao)으로 진입한 사용자를 회원으로 식별·생성한다.
- 회원을 **가입 단계(`registrationStatus`)** 로 관리한다: 소셜만 한 상태 → 학교 인증 완료 → 온보딩 완료.
- 학교 이메일 인증과 온보딩(닉네임·성별 입력)을 **순차적으로 강제**한다.
- 탈퇴를 소프트삭제(`activityStatus = WITHDRAWN`)로 처리해 감사 이력을 유지하고, 같은 카카오 계정·학교 이메일의 재가입을 30일 동안 제한한다(#107).
- 위 상태 전이 규칙(불변식)을 **엔티티 도메인 메서드 안**에 캡슐화한다(Rich Domain).

### 핵심 결정 (✅ 확정)

| 항목 | 결정 | 영향 |
|---|---|---|
| 엔티티 모델 | **새 스키마로 전면 재설계** | 소셜 제공 `name`과 온보딩 `nickName`을 **분리**. 기존 `nickname`/`status`는 마이그레이션 대상(§9) |
| 가입 단계 전이 | **학교인증 → 온보딩 순차 강제** | `UNVERIFIED → VERIFIED → COMPLETED` 단조 전진, 되돌리기 없음 |
| 탈퇴 처리 | **소프트삭제** (`ACTIVE/WITHDRAWN`) + `DELETE /api/members/me` (#107) | 행 유지(채팅·신고 FK 이력 보존). 탈퇴와 함께 진행 중 대화 종료·토큰 무효화 |
| 학교 이메일 | **유니크 제약** | 학교 이메일 1개당 계정 1개 보장(부분 유니크/NULL 허용) |
| 닉네임 | **유니크 제약** | 온보딩 `nickName` 중복 금지(부분 유니크/NULL 허용) |
| 재가입 | **30일 제한 후 새 회원으로 가입** (#107) | 탈퇴 후 30일 미만이면 같은 카카오 계정·학교 이메일 거부(`MEMBER_409_REJOIN_RESTRICTED`). 30일 후에는 이전 행의 식별자를 해제(익명화)하고 새 행으로 가입(학교 인증·온보딩부터 다시). 예전의 `reactivate()` 즉시 복구는 폐지 |

### 전제 / 선행

- 소셜 로그인·JWT 인증 흐름은 기존 체계를 그대로 사용한다(`docs/ARCHITECTURE.md § 8`).
- 매칭·채팅은 **온보딩 완료(`COMPLETED`)** 회원만 진입한다고 가정한다(채팅 도메인은 본 문서 범위 밖, [`docs/chat/CHAT_FEATURE.md`](../chat/CHAT_FEATURE.md)).
- 학교 이메일로 **어떤 메일 도메인을 학교로 인정할지**, 인증 코드 발송/검증 메커니즘은 본 문서 범위 밖(인증 서비스가 검증을 끝낸 결과만 도메인에 반영).
- 온보딩 완료 시 **`Feed` 엔티티가 자동 생성**된다. 피드는 `Member`의 공개 필드만 복사한 별도 엔티티로, 다른 회원의 피드 탐색 시 `Member` 전체를 노출하지 않는다([`docs/feed/FEED_DOMAIN.md`](../feed/FEED_DOMAIN.md)).

---

## 2. 도메인 모델

### 2-1. Member (`member`)

회원 1명. 소셜 로그인에서 받은 **불변에 가까운 식별 정보**(provider, providerId, name, email)와, 가입 과정에서 채워지는 **온보딩 정보**(nickName, schoolEmail), 그리고 **상태 필드**(role, activityStatus, registrationStatus)로 구성된다. 공개 프로필(생년·학과·MBTI·성별)은 `Member`가 아니라 `Feed`가 보유한다([`docs/feed/FEED_DOMAIN.md`](../feed/FEED_DOMAIN.md)).

| 필드 | 타입 / 제약 | 출처 / 설명 |
|---|---|---|
| `id` | PK, IDENTITY | |
| `provider` | `OAuthProvider`, `nullable=false`, len 20 | 소셜 로그인 제공자 |
| `providerId` | `String`, `nullable=false`, len 100 | 제공자 고유 ID. `(provider, providerId)` 유니크 |
| `name` | `String`, `nullable=false`, len 50 | **소셜 제공 이름** (표시용 닉네임과 별개) |
| `email` | `String`, nullable, len 100 | 소셜 제공 이메일. 이메일 동의 미수신 시 `null`(식별은 `(provider, providerId)`로 함) |
| `profileImageUrl` | `String`, nullable, len 500 | 소셜 제공 프로필 사진 |
| `nickName` | `String`, nullable, len 30 | **온보딩에서 입력**하는 닉네임. 가입 완료 전 `null`. **유니크** |
| `schoolEmail` | `String`, nullable, len 100 | 학교 인증 이메일. 인증 완료 시 세팅. **유니크** |
| `role` | `MemberRole`, `nullable=false`, len 20, default `USER` | 권한 |
| `activityStatus` | `ActivityStatus`, `nullable=false`, len 20, default `ACTIVE` | 탈퇴 등 활동 상태 |
| `registrationStatus` | `RegistrationStatus`, `nullable=false`, len 30, default `UNVERIFIED` | 가입 단계 |
| `withdrawnAt` | `LocalDateTime`, nullable | 탈퇴 시각. `withdraw(now)`에서 세팅·`reactivate()`에서 해제. **30일 재가입 제한(`withdrawnAt + 30일` 미만이면 제한)과 식별자 해제의 기준** (#107) |
| `identifiersReleasedAt` | `LocalDateTime`, nullable, col `identifiers_released_at` | 30일이 지나 카카오 식별자·학교 이메일·개인정보를 해제(익명화)한 시각(#107). `null`이면 해제 전 |
| `lastActiveAt` | `LocalDateTime`, nullable | 마지막 접속·주요 활동 시각. 로그인 시 갱신. "최근 접속 우선 노출" 정렬 기준 |
| `createdAt`/`updatedAt` | `BaseEntity` | 감사 필드. ⚠️ `updatedAt`은 프로필 갱신 등 임의 수정에도 변하므로 **탈퇴 시각 대용으로 쓰지 않는다**(→ `withdrawnAt` 사용) |

#### 제약 조건

| 제약 | 정의 | 목적 |
|---|---|---|
| `uk_member_provider` | `(provider, provider_id)` 유니크 | 동일 소셜 계정 중복 가입 방지 |
| `uk_member_school_email` | `school_email` 유니크 (✅ 결정) | 학교 이메일 1개당 계정 1개. `NULL`은 다중 허용(미인증 회원 공존) |
| `uk_member_nickname` | `nick_name` 유니크 (✅ 결정) | 온보딩 닉네임 중복 금지. `NULL`은 다중 허용(온보딩 전 회원 공존) |

### 2-2. Enum

| Enum | 값 | 비고 |
|---|---|---|
| `OAuthProvider` | `KAKAO` (확장 예정) | 소셜 로그인 제공자. 추가는 append |
| `MemberRole` | `USER("ROLE_USER")`, `ADMIN("ROLE_ADMIN")` | Spring Security authority 보유 |
| `Gender` | `MALE`, `FEMALE` | 온보딩 입력. ✅ 두 값으로 제한 |
| `ActivityStatus` | `ACTIVE`, `WITHDRAWN`, `SUSPENDED` | 소프트삭제 + **신고 제재 임시 정지(`SUSPENDED`, #75)**. 정지는 `ACTIVE`가 아니라 로그인·채팅·탐색에서 차단, 해제는 `reactivate()` |
| `RegistrationStatus` | `UNVERIFIED` → `VERIFIED` → `COMPLETED` | **선언 순서가 단계 의미를 가짐**(ordinal 비교). append만 허용 |

---

## 3. 상태 머신

### 3-0. 사용자 상태 모델 매핑 (✅ 기획 4상태 ↔ 도메인)

기획의 사용자 4상태는 **엔티티 존재 여부 + `registrationStatus`(+ `activityStatus`)** 조합으로 표현한다. `RegistrationStatus`는 로그인 이후 3단계와 1:1 대응하며, 비로그인(Guest)은 `Member` 행 부재로 표현한다(별도 enum 값 아님).

| 기획 상태 | 조건 | 도메인 표현 | 탐색/채팅 |
|---|---|---|---|
| **Guest** | 카카오 로그인 미완료 | `Member` 행 없음(비로그인) | X |
| **Unverified** | 로그인 O / 학교 인증 X | `registrationStatus = UNVERIFIED` | X |
| **Verified / Onboarding-Skipped** | 학교 인증 O / 온보딩 X | `registrationStatus = VERIFIED` | X (안내: "온보딩을 완료해야 사용할 수 있습니다") |
| **Active** | 학교 인증 + 온보딩 O | `registrationStatus = COMPLETED` **且** `activityStatus = ACTIVE` | O |

- 접근 판정은 `Member.canUseService()`(`isActive() && isOnboardingCompleted()`)로 한다 — 기획 'Active'에만 `O`.
- ⚠️ **이름 구분**: 기획 'Active'(가입 완료) ≠ `ActivityStatus.ACTIVE`(탈퇴 안 함). 둘은 서로 다른 축이므로 가입 단계 마지막 값은 `COMPLETED`로 둔다.
- Guest는 인증 계층(토큰 부재)에서 이미 차단되므로 도메인에 별도 값/파생 enum을 두지 않는다.

### 3-1. RegistrationStatus — 가입 단계 (✅ 확정: 순차 강제)

```
createOAuthMember()
        │
        ▼
 ┌────────────┐  verifySchoolEmail(email)  ┌──────────┐  completeOnboarding(nickName)  ┌───────────┐
 │ UNVERIFIED │ ─────────────────────────▶ │ VERIFIED │ ─────────────────────────────▶ │ COMPLETED │
 └────────────┘  (학교 이메일 세팅·유니크 검증)  └──────────┘  (nickName 세팅, 공개 프로필은 Feed)  └───────────┘
        ▲                                       │
        └──────────── 되돌리기 없음(단조 전진) ────────┘
```

- **순차 강제**: 학교 인증을 끝내야(`VERIFIED`) 온보딩을 진행할 수 있다. `UNVERIFIED`에서 바로 `completeOnboarding`을 호출하면 예외.
- **단조 전진**: 단계는 뒤로 가지 않는다. 이미 그 단계 이상이면 재호출은 예외(멱등이 아니라 위반으로 취급).
- 단계 비교는 enum 선언 순서(ordinal)를 이용한다. **따라서 값 순서 변경·중간 삽입 금지(append만 허용)**.
- 단계 비교 헬퍼(🧩): `RegistrationStatus.isAtLeast(other)` — 서비스가 ordinal을 직접 다루지 않도록 한다.

### 3-2. ActivityStatus — 탈퇴 / 재가입 (✅ 확정: 소프트삭제 + 30일 제한 후 새 가입, #107)

```
  createOAuthMember()
        │
        ▼
   ┌────────┐   withdraw(now)   ┌───────────┐  30일 경과 후 releaseIdentifiers(now)  ┌─────────────────────────┐
   │ ACTIVE │ ────────────────▶ │ WITHDRAWN │ ─────────────────────────────────────▶ │ WITHDRAWN (식별자 해제) │
   └────────┘                   └───────────┘   (로그인·학교 인증 시 즉시 / 정리 배치)  └─────────────────────────┘
                                                                                         같은 카카오 계정·학교 이메일로
                                                                                         새 Member 행을 만들 수 있음
```

- `WITHDRAWN`은 소프트삭제 상태다. 행을 삭제하지 않아 채팅·신고·차단 이력의 FK가 유지된다. 피드·사진·메시지·S3 객체도 지우지 않는다.
- `WITHDRAWN` 회원은 가입 단계 전이(`verifySchoolEmail`/`completeOnboarding`)·서비스 진입이 모두 차단된다. 탐색 후보·카드 상세·공개 피드·채팅 개설 게이트도 활성 회원만 통과시키므로 자연히 빠진다.
- **탈퇴 시각 기록(이슈 #39)**: `withdraw(now)`는 `withdrawnAt`을 기록한다. 서비스는 테스트 가능한 `Clock` 기준 시각을 넘긴다. `BaseEntity.updatedAt`은 임의 수정에도 갱신되어 탈퇴 시각으로 부정확하므로 전용 컬럼을 쓴다.
- **30일 재가입 제한(#107)**: 기준은 `withdrawnAt + 30일`(`Member.REJOIN_RESTRICTION`)이며, 그 시각 **미만**이면 제한하고 정확히 그 시각부터 허용한다(`isRejoinRestricted(now)`). 두 식별자에 각각 적용한다.
  - **같은 카카오 계정**: 로그인 시 탈퇴 회원을 찾고 제한 기간이면 재활성화·토큰 발급 없이 `MEMBER_409_REJOIN_RESTRICTED`로 거부한다. 메시지에 가입 가능 시각(`yyyy-MM-dd HH:mm`)을 담는다. 예전의 즉시 `reactivate()`는 제거했다.
  - **같은 학교 이메일**: 다른 카카오 계정이라도, 이메일 보유자가 제한 기간의 탈퇴 회원이면 인증 코드 발송과 최종 검증 모두 `MEMBER_409_REJOIN_RESTRICTED`로 거부한다. 활성(또는 정지) 회원이 쓰고 있으면 기존대로 `MEMBER_409_SCHOOL_EMAIL_DUPLICATED`. 이메일은 공백 제거·소문자로 정규화해 비교·저장한다.
- **30일 후 식별자 해제(#107)**: 탈퇴 행이 `(provider, provider_id)`·`school_email` 유니크 값을 계속 쥐면 30일 후에도 재가입할 수 없으므로, 제한이 끝난 탈퇴 회원은 `releaseIdentifiers(now)`로 익명화한다: `providerId = "withdrawn:{id}"`, `schoolEmail`·`email`·`profileImageUrl = null`, `name = "탈퇴한 회원"`, `identifiersReleasedAt = now`. 닉네임은 지난 채팅 이력에서 상대를 식별하는 표시명이라 남긴다.
  - **해제 시점**: 정리 배치(`WithdrawnMemberCleanupScheduler`, 1시간마다 최대 100명)가 주기적으로 해제하고, 배치가 늦어도 로그인·학교 이메일 검증 경로가 30일 지난 탈퇴 회원을 만나면 그 자리에서 해제한다(lazy cleanup). 정확성은 배치에 의존하지 않는다.
  - **재가입 = 새 회원**: 30일 후 같은 카카오 계정으로 로그인하면 이전 행은 익명화된 채 남고 새 `Member` 행이 `UNVERIFIED`로 생성된다(학교 인증·온보딩부터 다시). 이전 피드·키워드는 새 회원과 연결되지 않는다.
- **`SUSPENDED`(신고 제재 임시 정지, ✅ #75)**: 대상의 고유 신고자가 7일 내 **5명**에 도달하면 `ReportService`가 `suspend()`로 `ACTIVE → SUSPENDED` 자동 전이한다. 정지 회원은 `isActive()`가 `false`라 OAuth 로그인(`MEMBER_403_SUSPENDED`로 거부, 재활성화 안 함)·채팅·탐색이 모두 차단된다. 해제는 운영자 수동 `reactivate()`(관리자 도구는 후속 이슈). 탈퇴(`WITHDRAWN`)는 되살리지 않으려 `suspend()`가 `ACTIVE`일 때만 전이한다.

---

## 4. Rich Domain 설계 (도메인 메서드)

상태 변경은 setter가 아닌 **의도가 드러나는 도메인 메서드**로만 한다. 모든 전이 메서드는 진입 시 불변식을 검증하고, 위반 시 `BaseException.from(MemberErrorCode.*)`를 던진다.

### 4-1. 생성

```java
// 팩토리: 소셜 로그인 최초 진입
static Member createOAuthMember(
    OAuthProvider provider, String providerId,
    String name, String email, String profileImageUrl)
// → role=USER, activityStatus=ACTIVE, registrationStatus=UNVERIFIED
//   nickName/schoolEmail = null
```

### 4-2. 가입 단계 전이

```java
// UNVERIFIED → VERIFIED
void verifySchoolEmail(String schoolEmail)
//   불변식: 현재 UNVERIFIED && ACTIVE 여야 함. 아니면 예외.
//   schoolEmail 세팅. (유니크 충돌은 Service에서 DataIntegrityViolation → 도메인 예외 변환)

// VERIFIED → COMPLETED
void completeOnboarding(String nickName)
//   불변식: 현재 VERIFIED && ACTIVE 여야 함. UNVERIFIED면 "인증 먼저" 예외.
//   nickName 세팅. 공개 프로필(생년·학과·MBTI·성별)은 Feed가 보유(서비스가 FeedService.createFeed로 저장).
```

### 4-3. 소셜 프로필 동기화 (단계와 무관)

```java
// 재로그인 시 소셜 측 name/profileImageUrl 변경분만 갱신
void updateProfileFromOAuth(String name, String profileImageUrl)
//   변경된 경우에만 필드 갱신. 단계/상태는 건드리지 않는다.
//   주의: 동기화 대상은 소셜 'name'이며, 온보딩 'nickName'은 덮어쓰지 않는다.
```

### 4-4. 탈퇴 / 재활성화

```java
void withdraw(LocalDateTime now)   // withdraw()는 현재 시각으로 위임
//   불변식: 이미 WITHDRAWN이면 MEMBER_409_ALREADY_WITHDRAWN.
//   activityStatus = WITHDRAWN, withdrawnAt = now. (registrationStatus·온보딩 필드는 보존)

LocalDateTime rejoinAvailableAt()           // withdrawnAt + 30일. 탈퇴 회원이 아니면 null
boolean isRejoinRestricted(LocalDateTime now)  // now < rejoinAvailableAt()

void releaseIdentifiers(LocalDateTime now)
//   불변식: WITHDRAWN이고 재가입 제한이 끝났어야 함(아니면 IllegalStateException). 이미 해제했으면 no-op.
//   providerId = "withdrawn:{id}", schoolEmail·email·profileImageUrl = null, name = "탈퇴한 회원",
//   identifiersReleasedAt = now. nickName은 유지.

void reactivate()
//   불변식: 이미 ACTIVE면 예외.
//   activityStatus = ACTIVE, withdrawnAt = null. 신고 정지(SUSPENDED)의 운영자 수동 해제용이다.
//   탈퇴 회원의 재가입 경로에서는 더 이상 호출하지 않는다(#107).
```

> `Member`는 `@DynamicUpdate`로 바뀐 컬럼만 UPDATE한다. 탈퇴·학교 인증·로그인 활동 기록·신고 정지가 같은 행을 서로 다른 트랜잭션에서 고칠 수 있어, 전체 컬럼을 쓰면 먼저 읽어 둔 이전 활동 상태로 탈퇴를 덮어쓸 수 있기 때문이다. 이 경로들은 모두 회원 행을 쓰기 락(`findByIdForUpdate`)으로 읽어 직렬화한다.

### 4-6. 활동 시각 갱신 (단계와 무관)

```java
void recordActivity()
//   lastActiveAt = now(). 상태 전이가 아니므로 활동 상태와 무관하게 동작한다.
//   로그인 등 활동 시점에 서비스가 호출(findOrCreateByOAuth). 신규 회원은 생성 시 초기화된다.
```

### 4-5. 조회 헬퍼 (질의 메서드)

```java
boolean isActive()                 // activityStatus == ACTIVE
boolean isWithdrawn()              // activityStatus == WITHDRAWN
boolean isSchoolVerified()        // registrationStatus.isAtLeast(VERIFIED)
boolean isOnboardingCompleted()   // registrationStatus == COMPLETED
boolean canUseService()           // isActive() && isOnboardingCompleted()
```

### 불변식 요약 (객체지향 / 캡슐화)

1. 가입 단계는 **단조 전진** — `UNVERIFIED → VERIFIED → COMPLETED` 외 전이 불가, 되돌리기 불가.
2. 온보딩은 **학교 인증 선행** — `VERIFIED`가 아니면 `completeOnboarding` 불가.
3. `WITHDRAWN` 회원은 어떤 단계 전이도 불가.
4. `nickName`은 `completeOnboarding`에서만, `schoolEmail`은 `verifySchoolEmail`에서만 세팅. 공개 프로필(생년·학과·MBTI·성별)은 `Member`가 아닌 `Feed`가 보유.
5. 외부에서 상태 필드를 직접 변경하는 public setter를 제공하지 않는다.

---

## 5. 패키지 구조 (🧩 예정 / 일부 존재)

`docs/ARCHITECTURE.md § 3`의 레이어 서브패키지 규칙을 따른다.

```
com.blursome.member
├── controller/
│   └── MemberController.java          # 내 정보 조회, 학교인증, 온보딩, 탈퇴
├── service/
│   └── MemberService.java             # ✅ 존재 (가입 단계 전이 로직 추가 예정)
├── repository/
│   └── MemberRepository.java          # ✅ 존재
├── domain/
│   ├── Member.java                    # ✅ 존재 (스키마 재설계 대상 §9)
│   ├── OAuthProvider.java             # ✅ 존재
│   ├── MemberRole.java                # ✅ 존재
│   ├── Gender.java                    # 🧩 신규
│   ├── ActivityStatus.java            # 🧩 신규 (기존 MemberStatus 대체 §9)
│   └── RegistrationStatus.java        # 🧩 신규
├── dto/
│   ├── request/                       # SchoolEmailVerifyRequest, OnboardingRequest 등
│   └── response/                      # MemberProfileResponse 등
└── exception/
    └── MemberErrorCode.java           # ✅ 존재 (코드 추가 예정)
```

---

## 6. 유스케이스 흐름

### 6-1. 소셜 로그인 → 회원 식별/생성 (✅ 구현됨, 재설계 반영 예정)

```
[OAuth 결과: provider, providerId, name, email, profileImageUrl]
        │
        ▼
MemberService.findOrCreateByOAuth(userInfo)            @Transactional
   ├─ findIdByProviderAndProviderId 존재 → findByIdForUpdate(id)   (행이 있을 때만 잠금: 갭 락 교착 회피)
   │     ├─ SUSPENDED → MEMBER_403_SUSPENDED
   │     ├─ WITHDRAWN
   │     │     ├─ 30일 미만 → MEMBER_409_REJOIN_RESTRICTED (토큰 발급 없음, 메시지에 가입 가능 시각)
   │     │     └─ 30일 이상 → releaseIdentifiers(now) + flush → 새 회원 생성(아래)
   │     └─ ACTIVE → updateProfileFromOAuth(name, image) + recordActivity()
   └─ 없음 → Member.createOAuthMember(...)  (saveAndFlush, 유니크 충돌 시 MEMBER_409_OAUTH_CONFLICT)
        │
        ▼
   Member 반환 (registrationStatus로 후속 단계 분기)
```

### 6-2. 학교 이메일 인증

```
POST /api/members/me/school-email/verification-codes {schoolEmail}
MemberOnboardingService.sendSchoolEmailVerificationCode
   ├─ email = normalize(schoolEmail)          (strip + lowercase)
   ├─ 도메인 정책(@gs.anyang.ac.kr) 검증
   ├─ memberService.validateSchoolEmailAvailable(memberId, email)
   │     ├─ 다른 활성·정지 회원 보유 → MEMBER_409_SCHOOL_EMAIL_DUPLICATED
   │     └─ 30일 미만 탈퇴 회원 보유 → MEMBER_409_REJOIN_RESTRICTED
   └─ Redis에 (email, code) 저장 + 발송

POST /api/members/me/school-email/verifications {schoolEmail, code}
MemberOnboardingService.verifySchoolEmail                @Transactional
   ├─ findActiveMemberForUpdate(memberId)     (본인 행 잠금: 동시 탈퇴와 직렬화)
   ├─ Redis 코드·이메일 일치 확인
   ├─ memberService.releaseSchoolEmailForReuse(memberId, email)   (저장 직전 재검증)
   │     ├─ 보유자 id 조회 → findByIdForUpdate(보유자)
   │     ├─ 활성·정지 회원 → DUPLICATED / 30일 미만 탈퇴 → REJOIN_RESTRICTED
   │     └─ 30일 지난 탈퇴 회원 → releaseIdentifiers(now) + flush
   ├─ member.verifySchoolEmail(email)         (UNVERIFIED → VERIFIED)
   └─ 유니크 충돌(DataIntegrityViolation, 동시 인증) → MEMBER_409_SCHOOL_EMAIL_DUPLICATED
```

### 6-3. 온보딩 완료

```
POST /api/members/me/onboarding {nickName, birthYear, department, mbti, gender, interests[]}
        │
        ▼
MemberOnboardingService.completeOnboarding(memberId, request)     @Transactional
   ├─ findActiveMember(memberId)
   ├─ member.completeOnboarding(nickName)               (VERIFIED → COMPLETED, 닉네임만 세팅)
   │     └─ VERIFIED 아니면 MEMBER_409_SCHOOL_VERIFICATION_REQUIRED
   ├─ 닉네임 유니크 충돌(DataIntegrityViolation) → MEMBER_409_NICKNAME_DUPLICATED
   ├─ interests 저장(InterestCategory)
   └─ feedService.createFeed(member, gender, birthYear, department, mbti)   (공개 프로필 → Feed)
```

### 6-4. 탈퇴

```
DELETE /api/members/me        (본문 없음, 204 + refreshToken 쿠키 Max-Age=0)
        │
        ▼
MemberWithdrawalService.withdraw(memberId)               @Transactional (한 트랜잭션)
   ├─ memberService.findActiveMemberForUpdate(memberId)  (회원 행 잠금. 탈퇴·정지 회원은 MEMBER_403_INACTIVE)
   ├─ member.withdraw(now)                               (ACTIVE → WITHDRAWN)
   ├─ chatRoomService.closeRoomsOnMemberWithdrawal(memberId)
   │     └─ leftAt IS NULL 멤버십의 방을 roomId 오름차순으로 findByIdForUpdate
   │           ├─ 탈퇴 회원 멤버십 leave()
   │           └─ ACTIVE·BLOCKED → CLOSED, REPORTED는 상태 유지
   └─ publish MemberWithdrawnEvent
        │ AFTER_COMMIT
        ├─ RefreshTokenRevocationListener   → Redis RT 삭제
        └─ MemberWithdrawalCleanupListener  → Redis 학교 이메일 인증 코드 삭제
```

**정책 정리 (#107)**

| 항목 | 동작 |
|---|---|
| 응답 | `204 No Content`, `Set-Cookie: refreshToken=; Max-Age=0; Path=/api/auth` |
| 기존 Access Token | REST 인증 필터가 토큰 검증 뒤 `MemberAccessValidator`로 탈퇴 여부를 확인해 `403 MEMBER_403_INACTIVE` |
| 중복 탈퇴 | 같은 토큰으로 다시 요청하면 필터가 `403 MEMBER_403_INACTIVE`. 동시 요청 경합에서도 서비스가 잠금 뒤 비활성으로 보고 같은 `403`이다(정책 단일화) |
| 정지 회원 | 탈퇴 불가(`403 MEMBER_403_INACTIVE`). 탈퇴 후 30일 뒤 새 계정으로 제재를 우회하지 못하게 한다 |
| Refresh | 커밋 후 Redis RT를 지우고, 삭제가 실패해 RT가 남아도 refresh가 회원 활성 상태를 다시 확인해 거부 |
| STOMP | `CONNECT`에서 탈퇴 회원 거절, 방 토픽 `SUBSCRIBE` 차단. 탈퇴 전 연결한 세션의 송신·읽음은 멤버십 종료로 `ROOM_CLOSED` |
| 개인 알림 | 방을 나간 참여자는 새 메시지 개인 알림 대상(`findOtherMemberIds`)에서 제외 |
| 노출 | 탐색 후보·카드 상세·채팅 개설은 기존 활성 회원 게이트로 제외, 공개 피드 사진(`GET /api/feeds/{id}/images`)은 주인 활성 여부를 추가로 확인 |
| 동시성 | 잠금 순서는 "회원 → 방(roomId 오름차순)". 송신·나가기는 방만, 프로필·로그인·학교 인증은 회원만, 신고는 "회원 → 방"으로 같은 순서 |

### 6-5. 프로필 수정 (✅ 구현, 이슈 #95)

```
PUT /api/members/me/profile { nickName, mbti, keywordTagIds[] }   (수정 가능 항목 전체 전송)
        │
        ▼
MemberProfileService.updateProfile(memberId, req)               @Transactional
   ├─ memberService.findActiveMemberForUpdate(memberId)          (회원 행 PESSIMISTIC_WRITE — 동시 수정 직렬화)
   ├─ feed 없음 → MEMBER_403_ONBOARDING_REQUIRED
   ├─ 현재 값과 diff → 바뀐 항목 집합(NICKNAME / MBTI / KEYWORDS)
   ├─ 바뀐 항목 전부 member.validateProfileChangeable(item, now)  (하나라도 30일 미경과 → 전체 거절, 항목별 409)
   ├─ 닉네임 사전 중복 검사(existsByNickNameAndIdNot) → MEMBER_409_NICKNAME_DUPLICATED
   ├─ 관심사 검증(MemberKeywordService.resolveSelection — 기존 선택 태그는 비활성이어도 유지 허용)
   ├─ NICKNAME: member.changeNickName + feed.updateNickName → flush (유니크 충돌 → 409 NICKNAME_DUPLICATED)
   ├─ MBTI:     member.recordProfileChange(MBTI) + feed.changeMbti   (null = "모름")
   └─ KEYWORDS: member.recordProfileChange(KEYWORDS) + replaceSelection(추가/삭제분만 반영)
```

- **온보딩 API를 재사용하지 않는 이유**: 온보딩은 `VERIFIED → COMPLETED` 상태 전이 전용(완료 후 409)이고, 성별·생년·학과 같은 불변 필드와 피드 생성을 포함한다.
- **PUT(전체 전송) + 서버 diff**: MBTI는 `null`이 유효 값("모름")이라 PATCH의 "null=미변경"과 충돌한다. 프론트는 기존 프로필에 바뀐 값만 덮어 전체를 보내면 된다. 성별·생년·학과는 요청에 섞여 와도 무시한다.
- **30일 제한은 항목별**: `Member.profileEditHistory`(임베디드 `ProfileEditHistory`: `nick_name_changed_at`·`mbti_changed_at`·`keywords_changed_at`, 모두 nullable). 실제로 바뀐 항목의 시각만 갱신한다. 온보딩 입력은 "변경"으로 치지 않아 최초 1회는 바로 수정할 수 있다(`null` = 변경 이력 없음). 다음 변경 가능 시각 = 최근 변경 + 30일(그 시각부터 가능).
- **조회 응답**: `GET /api/members/me`의 `profileEdit.{nickName|mbti|keywords}` = `{ lastChangedAt, nextChangeableAt, changeable }`. 온보딩 미완료 회원은 수정 자체가 불가(`403 ONBOARDING_REQUIRED`)하므로 `profileEdit`을 `null`로 응답한다 — `changeable=true`로 오인되지 않도록 서버 검증 규칙과 응답을 일치시킨다.
- **닉네임 변경 판정은 대소문자 구분**: 요청 닉네임과 현재 닉네임을 `String.equals`로 비교한다. 대소문자만 바꾼 경우(`Abc` → `abc`)도 변경으로 보고 30일 제한을 적용한다(의도된 동작).

### 6-6. 닉네임 사용 가능 여부 (✅ 구현, 이슈 #95)

```
GET /api/members/me/nickname-availability?nickName=...   → 200 { nickName, available }
```

- 본인의 현재 닉네임은 `available=true`. 온보딩 중에도 쓸 수 있다(온보딩 완료를 요구하지 않음).
- 조회 시점 결과일 뿐이므로 온보딩·프로필 수정 저장 시 서버가 다시 검증한다(사전 검사 + 유니크 제약 충돌 변환).
- 비교는 DB collation 기준(유니크 제약과 같은 기준).

---

## 7. 데이터 접근

| 관심사 | 방식 |
|---|---|
| 소셜 계정으로 회원 조회 | `findByProviderAndProviderId(provider, providerId)` ✅ |
| 학교 이메일 중복 확인 | `existsBySchoolEmail(email)` 또는 유니크 제약 + 충돌 예외 변환 |
| 활성 회원 단건 | `findById` 후 `isActive()` 검증(`findActiveMember`) ✅ |
| 닉네임 중복 확인 | `existsByNickNameAndIdNot(nickName, id)`(본인 제외) + 유니크 제약 충돌 예외 변환 ✅ (#95) |
| 프로필 수정 직렬화 | `findByIdForUpdate(id)` — `PESSIMISTIC_WRITE` ✅ (#95) |

- Repository는 `JpaRepository<Member, Long>` 상속, 복잡 쿼리는 JPQL `@Query`(`ARCHITECTURE § 6`).

---

## 8. 에러 코드 (🧩 예정)

`com.blursome.member.exception.MemberErrorCode` (기존 enum 확장).

| 코드 | 상황 | HTTP |
|---|---|---|
| `MEMBER_404_NOT_FOUND` | 회원 없음 | 404 | ✅ |
| `MEMBER_403_INACTIVE` | 탈퇴/비활성 회원. 탈퇴 후 기존 Access Token·STOMP 접근, 중복 탈퇴, 정지 회원 탈퇴 요청(#107) | 403 | ✅ |
| `MEMBER_409_REJOIN_RESTRICTED` | 탈퇴 후 30일 미만에 같은 카카오 계정 로그인 또는 같은 학교 이메일 인증(#107). 메시지에 가입 가능 시각 | 409 | ✅ |
| `MEMBER_403_SUSPENDED` | 신고 제재로 정지된 회원(로그인 차단, #75) | 403 | ✅ |
| `MEMBER_409_OAUTH_CONFLICT` | 소셜 가입 동시 요청 충돌 | 409 | ✅ |
| `MEMBER_409_SCHOOL_EMAIL_DUPLICATED` | 학교 이메일 중복 | 409 | 🧩 |
| `MEMBER_409_SCHOOL_VERIFICATION_REQUIRED` | 인증 전 온보딩 시도 | 409 | 🧩 |
| `MEMBER_409_ALREADY_VERIFIED` | 이미 학교 인증됨 | 409 | 🧩 |
| `MEMBER_409_ALREADY_ONBOARDED` | 이미 온보딩 완료 | 409 | 🧩 |
| `MEMBER_409_ALREADY_WITHDRAWN` | 이미 탈퇴한 회원에 대한 도메인 `withdraw()` 직접 호출(API는 `MEMBER_403_INACTIVE`로 응답) | 409 | ✅ |
| `MEMBER_409_NICKNAME_DUPLICATED` | 닉네임 중복 | 409 | 🧩 |

---

## 9. 기존 코드 → 신규 스키마 마이그레이션 (🧩 영향 범위)

전면 재설계 결정에 따른 변경점. 인증/로그인 코드와 맞닿아 있으므로 함께 조정한다.

| 대상 | 현재 | 변경 |
|---|---|---|
| `Member.nickname` | 소셜 이름·표시 겸용 단일 필드 | **`name`(소셜) + `nickName`(온보딩) 분리** |
| `Member.status` (`MemberStatus`) | `ACTIVE/INACTIVE` | **`activityStatus`(`ActivityStatus`: ACTIVE/WITHDRAWN)** 로 대체 |
| 신규 필드 | — | `schoolEmail`(유니크), `registrationStatus` 추가 (공개 프로필 `gender`/생년/학과/MBTI는 `Feed`로 분리) |
| `OAuthUserInfo.nickname` | 소셜 이름을 `nickname`으로 전달 | `name`으로 명명 변경(소셜 이름 의미 명확화) |
| `Member.createOAuthMember(...)` | `nickname` 인자 | `name` 인자, `registrationStatus=UNVERIFIED` 초기화 |
| `Member.updateProfileFromOAuth(...)` | `nickname` 갱신 | `name` 갱신(온보딩 `nickName` 불변 유지) |

> `MemberStatus` enum은 `ActivityStatus`로 대체·삭제하고, 참조처(`MemberService.findActiveMember`, `isActive()`)를 함께 수정한다.

---

## 10. 결정 완료 / 남은 항목

### ✅ 결정 완료

| # | 항목 | 결정 |
|---|---|---|
| 1 | 엔티티 모델 | 새 스키마로 전면 재설계 (`name`/`nickName` 분리) |
| 2 | 가입 단계 전이 | 학교인증 → 온보딩 순차 강제, 단조 전진 |
| 3 | 탈퇴 처리 | 소프트삭제(`ACTIVE/WITHDRAWN`), `providerId` 보존 |
| 4 | 학교 이메일 | 유니크 제약(NULL 다중 허용) |
| 5 | 닉네임 | **유니크 제약**(NULL 다중 허용), 중복 시 `MEMBER_409_NICKNAME_DUPLICATED` |
| 6 | 재가입 | **기존 행 재활성화**(`reactivate()`) — 탈퇴 후 같은 소셜 계정 재로그인 시 직전 상태 그대로 복구 |
| 7 | 성별 | **`Gender` = MALE, FEMALE 로 제한** |
| 8 | 가입 단계 네이밍 | **`UNVERIFIED → VERIFIED → COMPLETED`** (기획 Unverified/Verified와 정렬, `COMPLETED`는 `ActivityStatus.ACTIVE`와 이름 충돌 회피). 기획 'Active'·'Guest'는 파생 접근상태로 보고 별도 enum 미도입(§3-0) |

| 9 | 활동성·탈퇴 타임스탬프 | **`withdrawnAt`·`lastActiveAt` 추가**(이슈 #39). 탈퇴 30일 보존 후 영구 삭제·최근 접속 정렬의 기준. `updatedAt`은 탈퇴 시각 대용으로 쓰지 않음 |

### ⏳ 남은 검토 항목

- **탈퇴 30일 보존 영구 삭제 스케줄러** — 설계(§3-2)는 확정. 배치 구현은 후속 이슈로 분리.
- **재가입 쿨다운** — 필요 시 `withdrawnAt + N일` 제한 추가(현재 미적용, 타임스탬프는 이미 확보).
- **학교 이메일 인증 메커니즘** — 인정 메일 도메인 화이트리스트, 인증 코드 발송/검증 흐름(인증 서비스 책임).
- **`SUSPENDED`(신고 제재 임시 정지)** — ✅ #75에서 도입(고유 신고자 5명 자동 정지 + 로그인/채팅/탐색 차단). 운영자 검토 큐·관리자 해제 API와 1명=검토 큐·발신 제한 단방향 정밀화는 후속.

---

## 부록: 관련 코드 위치

- 엔티티: `src/main/java/com/blursome/blursome/member/domain/`
- 서비스: `src/main/java/com/blursome/blursome/member/service/MemberService.java`
- 아키텍처 규칙: `docs/ARCHITECTURE.md`
- 채팅 연계(온보딩 완료 회원 진입): `docs/chat/CHAT_FEATURE.md`
