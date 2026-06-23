# 관심사 키워드 도메인 (Keyword Domain)

평면 enum(`InterestCategoryType`) 기반 관심사를 **카테고리 + 사전 태그 + 태그 간 관계(유사/보완/충돌)** 구조로
재설계한다. 탐색 알고리즘 키워드 점수(K, 45%)의 데이터 기반을 마련하고, 온보딩 키워드 선택·저장을 신규 구조로
전환한다. 관련 이슈 #36.

## 1. 설계 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 마스터 구조 | **DB 마스터 테이블**(카테고리/태그를 행으로) | 관계 FK 무결성, 무재배포 튜닝, 유일쌍 DB 강제. 태그는 참조 데이터라 enum보다 적합 |
| 관계 점수 | **`relation_type`만 저장, 점수는 코드 상수**(`RelationType.score`) | 검토 개선안 #6 고정 점수표(동일+10/유사+5/보완+3/충돌−4)와 일치, 일관성 |
| 외형 태그 | **본 도메인 제외**(매칭 비대상) | 강아지상 등은 프로필 표현용. 별도 도메인으로 분리 |
| 필수 카테고리 | **핵심만 필수**(`keyword_category.is_required`) | 온보딩 부담 완화(검토 개선안 #7). 기본 필수: 취미/여가·성격/대화·연락/관계 |

> 동일(+10)은 관계 행이 아니라 두 회원의 `tag_id` 일치로 판정한다. 다양성 보너스 등 합산·정규화는 탐색
> 알고리즘(Phase 2)에서 처리한다. 본 이슈는 그 입력 데이터(선택·관계)까지 책임진다.

## 2. 테이블

| 테이블 | 역할 | 핵심 제약 |
|---|---|---|
| `keyword_category` | 카테고리 마스터(8종) | UNIQUE(code), UNIQUE(name), `is_required` |
| `keyword_tag` | 카테고리별 사전 태그 | FK(category_id), UNIQUE(code), UNIQUE(category_id, name), `is_active` |
| `member_keyword` | 회원의 태그 선택(N) | FK(member_id, tag_id), UNIQUE(member_id, tag_id), INDEX(tag_id) |
| `keyword_relation` | 태그 간 관계(유사/보완/충돌) | FK(tag_a_id, tag_b_id), UNIQUE(tag_a_id, tag_b_id), CHECK(tag_a_id < tag_b_id) |

### 무결성 포인트 (검토 개선안 #2·#3)
- `keyword_relation`은 항상 `tag_a_id < tag_b_id`로 정규화(`KeywordRelation.of`가 정렬) → (A,B)/(B,A) 중복 차단.
- `UNIQUE(tag_a_id, tag_b_id)`는 유형을 포함하지 않는다 → **한 쌍은 한 관계 유형만**(보완↔충돌 양립 차단).
  예: `즉흥형↔계획형`은 보완으로만, `운동↔산책`은 유사로만 등재.

## 3. 시드 (`keyword/seed`)

- `KeywordSeedData` — 8개 카테고리·태그·관계 정의(기획 v1.1 §7.3·§7.4). `sortOrder`는 선언 순서.
- `KeywordSeeder`(`ApplicationRunner`) — 비어 있을 때만 삽입하는 **멱등** 시더(마이그레이션 툴 미사용 구성).
  `blursome.keyword.seed.enabled=false`로 비활성화 가능(테스트 등).
- 충돌(CONFLICT) 쌍은 기획 §7.4.4 충돌 기준 정규화 후 보강(현재 미등재). 유형 enum은 이미 포함.

## 4. 온보딩 연동

- 요청: `OnboardingRequest.keywordTagIds`(태그 id 목록). 카탈로그(`GET /api/keywords`)에서 받은 id를 제출.
- 검증(서비스): ① 태그 존재·활성, ② 필수 카테고리(`is_required`)마다 최소 1개 선택.
- 저장: `member_keyword` 행. 응답은 선택 키워드(카테고리·태그) 목록.

## 5. API

| Method | Path | 설명 | 인증 |
|---|---|---|---|
| GET | `/api/keywords` | 카테고리(정렬·필수 여부) + 활성 태그 카탈로그 | 공개 |
| POST | `/api/members/me/onboarding` | 온보딩(키워드 태그 id 포함) | 필요 |

## 6. 에러 코드 (`KeywordErrorCode`)

| 코드 | 상황 | HTTP |
|---|---|---|
| `KEYWORD_400_TAG_NOT_FOUND` | 존재하지 않거나 비활성 태그 포함 | 400 |
| `KEYWORD_400_REQUIRED_CATEGORY_MISSING` | 필수 카테고리 미선택 | 400 |

## 7. 마이그레이션 / 후속

- 기존 `interest_category`(`InterestCategory`/`InterestCategoryType`)는 제거하고 `member_keyword`로 대체(클린 교체).
  운영 환경에는 신규 테이블 DDL을 적용해야 한다(prod `ddl-auto: validate`).
- Phase 2: 키워드 점수(K) 계산·정규화·카테고리 상한·다양성 보너스(탐색 알고리즘), 외형 태그 도메인, 학과 마스터(#40).
