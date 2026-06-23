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
- 탈퇴를 소프트삭제(`activityStatus = WITHDRAWN`)로 처리해 감사·재가입 추적을 유지한다.
- 위 상태 전이 규칙(불변식)을 **엔티티 도메인 메서드 안**에 캡슐화한다(Rich Domain).

### 핵심 결정 (✅ 확정)

| 항목 | 결정 | 영향 |
|---|---|---|
| 엔티티 모델 | **새 스키마로 전면 재설계** | 소셜 제공 `name`과 온보딩 `nickName`을 **분리**. 기존 `nickname`/`status`는 마이그레이션 대상(§9) |
| 가입 단계 전이 | **학교인증 → 온보딩 순차 강제** | `UNVERIFIED → VERIFIED → COMPLETED` 단조 전진, 되돌리기 없음 |
| 탈퇴 처리 | **소프트삭제** (`ACTIVE/WITHDRAWN`) | 행 유지, `providerId` 보존으로 재가입/감사 추적. `SUSPENDED`는 후순위 |
| 학교 이메일 | **유니크 제약** | 학교 이메일 1개당 계정 1개 보장(부분 유니크/NULL 허용) |
| 닉네임 | **유니크 제약** | 온보딩 `nickName` 중복 금지(부분 유니크/NULL 허용) |
| 재가입 | **기존 행 재활성화** | 탈퇴자 재로그인 시 WITHDRAWN 행을 `reactivate()` — 직전 상태 그대로 복구 |

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
| `createdAt`/`updatedAt` | `BaseEntity` | 감사 필드 |

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
| `ActivityStatus` | `ACTIVE`, `WITHDRAWN` | 소프트삭제. `SUSPENDED`(관리자 정지)는 후순위 append |
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

### 3-2. ActivityStatus — 탈퇴 / 재가입 (✅ 확정: 소프트삭제 + 재활성화)

```
  createOAuthMember()
        │
        ▼
   ┌────────┐   withdraw()    ┌───────────┐
   │ ACTIVE │ ──────────────▶ │ WITHDRAWN │
   └────────┘                 └───────────┘
        ▲                          │
        └──── reactivate() ────────┘
          (같은 소셜 계정 재로그인)
```

- `WITHDRAWN`은 소프트삭제 상태. 행을 삭제하지 않고 `providerId`를 보존해 감사·재가입 추적이 가능하다.
- `WITHDRAWN` 회원은 가입 단계 전이(`verifySchoolEmail`/`completeOnboarding`)·서비스 진입이 모두 차단된다.
- **재가입(✅ 결정: 기존 행 재활성화)**: 탈퇴자가 같은 소셜 계정으로 재로그인하면 `findByProviderAndProviderId`가 기존 WITHDRAWN 행을 찾는다(새 행 생성은 `uk_member_provider` 충돌이라 불가). 이때 `reactivate()`로 `activityStatus`만 `ACTIVE`로 되돌리고 **`registrationStatus`·온보딩 필드(`nickName`/`schoolEmail`)는 보존된 값 그대로 복구**한다(닉네임·학교인증 재수행 불필요). 공개 프로필을 담은 `Feed`도 그대로 유지된다.
- (후순위 ⏳) 재가입 쿨다운이 필요하면 `withdrawnAt` 타임스탬프 + N일 제한을 추가한다. 1차는 미적용.
- `SUSPENDED`(관리자 정지)는 후순위 — 필요 시 enum append.

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
void withdraw()
//   불변식: ACTIVE 여야 함. 이미 WITHDRAWN이면 예외.
//   activityStatus = WITHDRAWN. (registrationStatus·온보딩 필드는 보존)

void reactivate()
//   불변식: WITHDRAWN 여야 함. 이미 ACTIVE면 예외.
//   activityStatus = ACTIVE. registrationStatus·nickName·schoolEmail은 건드리지 않는다(직전 상태 복구).
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
   ├─ findByProviderAndProviderId 존재
   │     ├─ WITHDRAWN 이면 → member.reactivate()        (재가입: 직전 상태 복구)
   │     └─ updateProfileFromOAuth(name, image)
   └─ 없음 → Member.createOAuthMember(...)  (saveAndFlush, 유니크 충돌 시 도메인 예외)
        │
        ▼
   Member 반환 (registrationStatus로 후속 단계 분기)
```

### 6-2. 학교 이메일 인증

```
[인증 서비스가 학교 이메일 소유 검증 완료]
        │
        ▼
MemberService.verifySchoolEmail(memberId, schoolEmail)  @Transactional
   ├─ findActiveMember(memberId)
   ├─ member.verifySchoolEmail(schoolEmail)   (UNVERIFIED → VERIFIED)
   └─ 유니크 충돌(DataIntegrityViolation) → MEMBER_409_SCHOOL_EMAIL_DUPLICATED
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
DELETE /api/members/me
        │
        ▼
MemberService.withdraw(memberId)                        @Transactional
   └─ member.withdraw()   (ACTIVE → WITHDRAWN, 소프트삭제)
```

---

## 7. 데이터 접근

| 관심사 | 방식 |
|---|---|
| 소셜 계정으로 회원 조회 | `findByProviderAndProviderId(provider, providerId)` ✅ |
| 학교 이메일 중복 확인 | `existsBySchoolEmail(email)` 또는 유니크 제약 + 충돌 예외 변환 |
| 활성 회원 단건 | `findById` 후 `isActive()` 검증(`findActiveMember`) ✅ |
| 닉네임 중복 확인 | `existsByNickName(nickName)` 또는 유니크 제약 + 충돌 예외 변환 |

- Repository는 `JpaRepository<Member, Long>` 상속, 복잡 쿼리는 JPQL `@Query`(`ARCHITECTURE § 6`).

---

## 8. 에러 코드 (🧩 예정)

`com.blursome.member.exception.MemberErrorCode` (기존 enum 확장).

| 코드 | 상황 | HTTP |
|---|---|---|
| `MEMBER_404_NOT_FOUND` | 회원 없음 | 404 | ✅ |
| `MEMBER_403_INACTIVE` | 탈퇴/비활성 회원 | 403 | ✅ |
| `MEMBER_409_OAUTH_CONFLICT` | 소셜 가입 동시 요청 충돌 | 409 | ✅ |
| `MEMBER_409_SCHOOL_EMAIL_DUPLICATED` | 학교 이메일 중복 | 409 | 🧩 |
| `MEMBER_409_SCHOOL_VERIFICATION_REQUIRED` | 인증 전 온보딩 시도 | 409 | 🧩 |
| `MEMBER_409_ALREADY_VERIFIED` | 이미 학교 인증됨 | 409 | 🧩 |
| `MEMBER_409_ALREADY_ONBOARDED` | 이미 온보딩 완료 | 409 | 🧩 |
| `MEMBER_409_ALREADY_WITHDRAWN` | 이미 탈퇴한 회원 | 409 | 🧩 |
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

### ⏳ 남은 검토 항목

- **재가입 쿨다운** — 탈퇴 후 N일 재가입 제한 도입 시 `withdrawnAt` 추가(1차 미적용).
- **학교 이메일 인증 메커니즘** — 인정 메일 도메인 화이트리스트, 인증 코드 발송/검증 흐름(인증 서비스 책임).
- **`SUSPENDED`(관리자 정지)** 도입 시점.

---

## 부록: 관련 코드 위치

- 엔티티: `src/main/java/com/blursome/blursome/member/domain/`
- 서비스: `src/main/java/com/blursome/blursome/member/service/MemberService.java`
- 아키텍처 규칙: `docs/ARCHITECTURE.md`
- 채팅 연계(온보딩 완료 회원 진입): `docs/chat/CHAT_FEATURE.md`
