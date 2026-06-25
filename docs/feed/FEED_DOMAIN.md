# 피드 도메인 설계 문서

BlurSome의 피드(Feed) 도메인을 정의합니다. 온보딩을 완료한 회원의 공개 프로필을 별도 엔티티로 분리해, 다른 회원의 피드 탐색 시 `Member` 전체를 노출하지 않고 필요한 정보만 조회할 수 있게 합니다.

> 상태 표기
> - ✅ **확정**: 의사결정으로 본 문서/코드에 반영됨
> - 🧩 **설계(제안)**: 방향은 정했으나 일부 후속 보강 예정
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따릅니다.

---

## 1. 개요

### 목표

- `Member` 엔티티의 민감 정보(provider, providerId, email, schoolEmail, registrationStatus 등)를 피드 조회에서 원천 차단한다.
- 공개 프로필(`gender`·`birthYear`·`department`·`mbti`)은 **`Feed`가 단독으로 보유**한다. 이 필드들은 `Member`에서 제거했으며(중복 제거), 온보딩 시 곧바로 `Feed`에 채운다.
- 온보딩 완료 시 `Member`와 1:1로 `Feed`를 자동 생성해, 이후 피드 탐색 쿼리가 `Member`를 조회하지 않고 `Feed`만으로 완결되도록 한다.
- `nickName`만 `Member`와 중복 보유한다(비정규화). 닉네임은 `Member`의 가입 정체성이라 양쪽에 두고, 피드 탐색 시 JOIN 없이 조회한다. `Member.nickName`이 현재 불변(변경 API 없음)이므로 동기화 부담이 없다.

### 핵심 결정 (✅ 확정)

| 항목 | 결정 | 비고 |
|---|---|---|
| 공개 프로필 소유권 | **`gender`·`birthYear`·`department`·`mbti`는 `Feed` 단독 보유** | `Member`에서 제거. 온보딩이 `Feed` 컬럼을 채움 |
| 피드 생성 시점 | **온보딩 완료(`completeOnboarding`) 트랜잭션 내** | 온보딩 롤백 시 피드도 함께 롤백 |
| `Member` 관계 | **`@OneToOne(LAZY)`**, `uk_feed_member` 유니크 | DB 레벨에서 회원당 피드 1개 보장 |
| `nickName` 정책 | **`Member`와 중복 보유(비정규화)** | 피드 탐색 JOIN 제거. 닉네임 불변 동안 동기화 비용 없음 |
| `nullable` 정책 | **공개 프로필 4개 필드 `nullable=false`** | `Feed`는 온보딩 완료 후에만 생성 → 값이 항상 존재. `Member`(nullable)와 의도적으로 다름 |

---

## 2. 도메인 모델

### 2-1. Feed (`feed`)

온보딩 완료 회원의 공개 프로필. `Member`와 1:1 대응하며, 피드 탐색에 필요한 필드만 포함한다.

| 필드 | 타입 / 제약 | 출처 |
|---|---|---|
| `id` | PK, IDENTITY | — |
| `member` | `Member` `@OneToOne(LAZY)`, `member_id`, `nullable=false` | `Member.id` |
| `nickName` | `String`, `nullable=false`, len 30, **유니크** | `Member.nickName` 복사(중복 보유) |
| `gender` | `Gender`(enum, STRING), `nullable=false`, len 10 | 온보딩 요청 (Feed 단독 보유) |
| `birthYear` | `Integer`, `nullable=false`, col `birth_year` | 온보딩 요청 (Feed 단독 보유) |
| `department` | `Department`(enum, STRING), `nullable=false`, len 50 | 온보딩 요청 (Feed 단독 보유). 학과 정규화(이슈 #40) — 자유 문자열 대신 고정 enum이며 소속 계열(`College`)을 보유해 탐색 점수의 동일 학과(+1.0)/동일 계열(+0.5) 가산 기준이 된다(Phase 2) |
| `mbti` | `Mbti`(enum, STRING), **`nullable=true`**, len 4 | 온보딩 요청 (Feed 단독 보유). **선택값**: null="모름"(#76). 탐색 점수에서 한쪽이라도 null이면 M 제외·30% 재분배 |
| `createdAt`/`updatedAt` | `BaseEntity` | — |

> **`nullable=false` 이유**: `Feed`는 온보딩 완료 이후에만 생성되므로 공개 프로필 4개 필드는 항상 값이 채워진다. `Gender`/`Mbti` enum은 `member.domain` 패키지에 그대로 두고 `Feed`가 참조한다(온보딩 요청 DTO도 동일 enum 사용).

#### 제약 조건

| 제약 | 정의 | 목적 |
|---|---|---|
| `uk_feed_member` | `member_id` 유니크 | 회원당 피드 1개(1:1) DB 레벨 보장 |
| `uk_feed_nick_name` | `nick_name` 유니크 | `Member.uk_member_nickname`과 동일 수준의 무결성 유지 |

---

## 3. `Member`와의 관계

```
Member (1) ──────────── (1) Feed
  └── onboarding 완료 시 Feed.createOnOnboarding(member, gender, birthYear, department, mbti) 호출
```

- `Member`는 가입 정체성·상태(provider/식별/`nickName`/`schoolEmail`/단계)만, `Feed`는 공개 프로필을 갖는다.
- 공개 프로필 4개 필드(`gender`/`birthYear`/`department`/`mbti`)는 **`Member`에서 제거**되어 `Feed`에만 존재한다.
- `Member.getMyProfile` 응답에 이 4개 필드를 채울 때는 `Feed`를 조회한다(온보딩 미완료 회원은 `Feed`가 없어 `null`).

### 피드에 포함되지 않는 Member 필드 (의도적 제외)

| 필드 | 제외 이유 |
|---|---|
| `provider`, `providerId` | OAuth 내부 식별자, 노출 불필요 |
| `name` | 카카오 실명, 개인정보 |
| `email` | 카카오 이메일, 개인정보 |
| `schoolEmail` | 학교 이메일, 개인정보 |
| `profileImageUrl` | 추후 피드 전용 블러 이미지로 별도 관리 예정 (⏳) |
| `role` | 내부 권한 정보 |
| `activityStatus` | 내부 상태 |
| `registrationStatus` | 내부 가입 단계 |

---

## 4. 온보딩 연동 플로우

```
POST /api/members/me/onboarding
        │
        ▼
MemberOnboardingService.completeOnboarding()              @Transactional
   ├─ member.completeOnboarding(nickName)                 (VERIFIED → COMPLETED, 닉네임만 세팅)
   ├─ memberRepository.flush()                            (닉네임 유니크 충돌 즉시 감지)
   ├─ interestCategoryRepository.saveAll(...)             (관심사 저장)
   └─ feedService.createFeed(member, gender, birthYear, department, mbti)
        └─ Feed.createOnOnboarding(member, gender, ...)   (닉네임은 member에서, 4개 필드는 요청 값)
        └─ feedRepository.save(feed)
        │
        ▼
 [Feed 행 생성 완료, 온보딩 트랜잭션과 원자적 처리]
```

- `FeedService.createFeed`는 `@Transactional(REQUIRED)` 기본값으로, 호출 측 트랜잭션에 합류한다.
- `memberRepository.flush()` 이후에 호출하므로, 닉네임 중복이면 예외가 먼저 발생해 피드 생성에 도달하지 않는다.

---

## 5. nickName 중복 보유 정책

### 현재 (✅ 확정)

| 항목 | 내용 |
|---|---|
| 정책 | `Member.nickName`을 `Feed.nickName`에 그대로 복사 |
| 동기화 | 현재 닉네임 변경 API 없음 → 동기화 불필요 |
| 유니크 | `Member`와 `Feed` 양쪽에 유니크 제약 적용 |

### 닉네임 변경 기능 추가 시 (⏳ 미정)

닉네임 변경 API가 추가되면 `Feed.updateNickName()`을 이용한 동기화 로직이 필요하다.

```
Member.nickName 변경 요청
        ↓
MemberService: member.updateNickName(newNickName) + flush (유니크 검증)
        ↓ (AFTER_COMMIT — 혹은 동일 트랜잭션 내)
FeedService: feed.updateNickName(newNickName)
```

`Feed.updateNickName(String nickName)` 메서드는 이미 준비되어 있다.

---

## 6. 패키지 구조

```
com.blursome.feed
├── domain/
│   └── Feed.java                    ✅ 생성됨
├── repository/
│   └── FeedRepository.java          ✅ 생성됨
├── service/
│   └── FeedService.java             ✅ 생성됨
└── exception/
    └── FeedErrorCode.java           ✅ 생성됨
```

---

## 7. 에러 코드

`com.blursome.feed.exception.FeedErrorCode`

| 코드 | 상황 | HTTP |
|---|---|---|
| `FEED_404_NOT_FOUND` | 피드를 찾을 수 없음 | 404 |

---

## 8. 남은 항목 (⏳ TODO)

- **피드 탐색 API 구현** — 관심사 기반 다른 회원 피드 조회 (`GET /api/feeds?interest=BASKETBALL`). `FeedRepository`에 관심사 필터링 쿼리 추가 필요.
- **`profileImageUrl` 관리** — 현재 `Member.profileImageUrl`(카카오 URL)이 피드에 없음. 추후 블러 처리된 이미지 URL을 `Feed`에 별도 보관할 예정.
- **닉네임 변경 동기화** — 닉네임 변경 API 도입 시 `Feed.updateNickName()` 연동.
- **피드 공개 여부** — 회원이 피드 노출을 끄는 기능 도입 시 `Feed.visible` 컬럼 추가.

---

## 부록: 관련 코드 위치

- 엔티티: `src/main/java/com/blursome/blursome/feed/domain/Feed.java`
- 피드 서비스: `src/main/java/com/blursome/blursome/feed/service/FeedService.java`
- 온보딩 연동: `src/main/java/com/blursome/blursome/member/service/MemberOnboardingService.java`
- 회원 도메인: [`docs/member/MEMBER_DOMAIN.md`](../member/MEMBER_DOMAIN.md)
- 온보딩 플로우: [`docs/member/MEMBER_ONBOARDING.md`](../member/MEMBER_ONBOARDING.md)
