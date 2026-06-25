# 탐색(Discovery) 도메인 설계

온보딩을 완료한 회원을 서로에게 추천·탐색시키는 도메인을 정의한다. 후보 필터로 추려낸 회원들을
**가중 점수(키워드 K·MBTI M·년생 B·학과 D)** 로 정렬해 카드 형태로 노출한다. 관련 이슈 #67.

> 상태 표기
> - ✅ **확정**: 의사결정으로 본 문서/스키마에 반영됨 (원안: BlurSome 마스터 설계 §17)
> - 🧩 **설계(제안)**: 방향은 정했으나 구현 전 — 검토 후 조정 가능
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따른다.

---

## 1. 개요

### 목표
- 온보딩 완료 회원을 viewer 기준으로 **필터링 → 점수화 → 정렬 → 페이지네이션**하여 탐색 목록을 제공한다.
- 점수 재료(키워드 관계·학과·MBTI·년생·최근접속)는 이미 도메인에 존재하므로, 본 도메인은 **합산·정규화·정렬 정책**과 **후보 질의**를 책임진다.
- MVP는 **최신순**으로 먼저 제공하고, 가중 점수 정렬을 후속 단계로 얹는다.

### 핵심 결정 (✅ 확정 — 마스터 §17)

| 항목 | 결정 | 근거 |
|---|---|---|
| 점수 모델 | `Score = 0.45·K + 0.30·M + 0.15·B + 0.10·D` (각 0~1 정규화) | 키워드 우선, 성향(MBTI)·년생·학과 보조 |
| 활동성 | 가중치에서 **제외**, **"최근 7일 접속 우선" 타이브레이커**로만 사용 | 신선도 보정이되 점수 왜곡 방지 |
| MBTI 모름 | 한쪽이라도 모름이면 `M=0`, **30%를 잔여 항목에 비례 재분배**(K 64.3 / B 21.4 / D 14.3) | 결측이 매칭을 부당히 깎지 않게 |
| 탐색 대상 | **이성**(viewer와 반대 성별)만 | 기획 정책 |
| MVP 정렬 | **최신순**(`created_at desc`) 후 가중치 도입 | 점진 출시 |

### 전제
- viewer는 **온보딩 완료(`COMPLETED`) + 활성** 회원이다(미완료자는 탐색 진입 불가, `Member.canUseService()`).
- 공개 프로필(성별·년생·학과·MBTI)은 [`Feed`](../feed/FEED_DOMAIN.md)가, 관심사는 `MemberKeyword`가 보유한다.

---

## 2. 후보 집합 (필터) ✅

viewer 기준 아래를 **모두** 만족하는 회원만 후보다.

| 조건 | 구현 기준 |
|---|---|
| 이성 | `feed.gender = (viewer의 반대 성별)` |
| 온보딩 완료 | `member.registration_status = COMPLETED` **且** `feed` 존재 |
| 활성 | `member.activity_status = ACTIVE` **且** `member.withdrawn_at IS NULL` |
| 본인 제외 | `member.id <> viewerId` |
| 차단 없음 | `block`에 (viewer↔후보) 어느 방향도 없음 — ✅ 구현(#41) |
| 이미 채팅 중 | **포함**(탐색에서 다시 보여도 됨, 채팅 시작은 기존 방으로 이동) |

> ✅ **#41(차단 도메인) 반영**: 후보 쿼리에 `not exists (block where 양방향)`을 추가해 차단/피차단을 제외한다.

---

## 3. 점수 모델 ✅

$$ Score = 0.45 \times K + 0.30 \times M + 0.15 \times B + 0.10 \times D $$

각 항목은 0~1로 정규화한다.

| 항목 | 가중치 | 입력 | 계산 |
|---|---|---|---|
| **K 키워드** | 45% | `member_keyword`, `keyword_relation` | 동일+10/유사+5/보완+3/충돌−4 + 다양성+5 합산 후 정규화(카테고리 상한) |
| **M MBTI** | 30% | `feed.mbti` | 동일=1.0, (선택)궁합표/100. **한쪽 모름 = 항목 제외** |
| **B 년생** | 15% | `feed.birthYear` | `1 − min(|Δ년|, maxΔ) / maxΔ` (maxΔ=10) |
| **D 학과** | 10% | `Department.college` | 동일 학과=1.0 / 동일 계열=0.5 / 그 외=0 |

### MBTI 모름 재분배 ✅
viewer/후보 중 하나라도 MBTI를 모르면 `M=0`으로 두고, 30%를 잔여 세 항목 비율로 재분배한다.

```
K' = 0.45 / 0.70 ≈ 0.643
B' = 0.15 / 0.70 ≈ 0.214
D' = 0.10 / 0.70 ≈ 0.143
Score = K'·K + B'·B + D'·D
```

> 🧩 **`feed.mbti` nullable 전환 필요**: "모름"을 표현하려면 현재 `NOT NULL`인 `feed.mbti`를 **nullable**로 바꾸고 온보딩에서 "모름" 입력을 허용해야 한다(현재는 필수). 전환 전까지 M은 항상 동일/궁합 계산만 적용된다.

### 활동성 타이브레이커 ✅
점수가 같으면 **최근 7일 내 접속자**(`member.last_active_at >= now()-7d`)를 먼저, 그다음 `last_active_at` 내림차순으로 정렬한다(가중치에는 미반영).

---

## 4. 키워드 점수(K) ✅

```
viewer_tags, cand_tags = member_keyword(각 회원의 tag_id 집합)
raw  = 10 × |viewer_tags ∩ cand_tags|                 // 동일 태그
for a in viewer_tags, b in cand_tags (a ≠ b):
    rel = keyword_relation[min(a,b)][max(a,b)]        // 유일 쌍 조회(없으면 무관계)
    SIMILAR:+5 / COMPLEMENT:+3 / CONFLICT:−4
if (서로 다른 카테고리 3개 이상에서 +가 발생): raw += 5   // 다양성 보너스
K = normalize(raw)                                    // 카테고리당 상한 적용 후 0~1
```

- 관계 점수는 `RelationType`의 코드 상수(SIMILAR +5 / COMPLEMENT +3 / CONFLICT −4)를 사용한다. **동일(+10)은 관계 행이 아니라 `tag_id` 일치로 판정**한다([`KEYWORD_DOMAIN.md`](../keyword/KEYWORD_DOMAIN.md)).
- `keyword_relation`은 항상 `tag_a_id < tag_b_id` 정규화 쌍이므로 `(min,max)`로 조회한다.
- 외형(`is_appearance`) 태그는 매칭 비대상 — K에서 제외한다. **현재 외형 태그 도메인은 미구현**이라 당장은 해당 없음(향후 분리 시 제외 필터 추가).
- 정규화 상한·다양성 임계(3 카테고리)는 구현 시 상수로 두고 튜닝한다(🧩).

---

## 5. API (🧩 제안)

```
GET /api/discovery?cursor={opaque}&size={n}     (인증 필요, 온보딩 완료자만)
→ 200 { items: [FeedCard...], nextCursor }
```

- **FeedCard**: 노출용 공개 프로필(닉네임·년생·학과·MBTI·성별·블러 대표 이미지·선택 키워드 일부). 원본 사진은 채팅 단계 공개 정책을 따른다(탐색에는 블러본만).
- **커서 페이지네이션**: MVP는 `created_at, id` 복합 커서(최신순). 점수 정렬 단계에서는 `(score, last_active_at, id)` 기반 커서 또는 캐시된 순위 인덱스를 쓴다(§6).

---

## 6. 성능 전략 ✅/🧩

| 규모 | 전략 |
|---|---|
| MVP(~1,000) | 필터 후보 수십~수백을 **앱 레이어에서 점수 계산→정렬→페이지네이션**. `keyword_relation`은 메모리 캐시. MVP 기본 정렬은 최신순 |
| 성장(~10,000) | viewer별 추천 결과 **Redis 캐시(5분 TTL)**, 프로필/키워드 변경 시 무효화 (🧩) |
| 확장 | `discovery_score` 스냅샷 테이블을 배치/이벤트로 갱신 (⏳) |

### 권장 인덱스 ✅
```sql
CREATE INDEX idx_member_discovery     ON member(gender, activity_status, registration_status, created_at);
CREATE INDEX idx_member_keyword_member ON member_keyword(member_id);
CREATE INDEX idx_member_keyword_tag    ON member_keyword(tag_id);
-- block: uk_block_pair(blocker_id, blocked_id) + idx_block_blocked(blocked_id) (#41 반영)
```
> 주: `gender`는 현재 `feed`에 있으므로 후보 질의는 `member ⨝ feed` 조인 기준으로 인덱스를 잡는다(실제 컬럼 위치에 맞춰 조정).

---

## 7. 단계별 구현 계획

### Phase A — MVP(최신순) 골격
1. 후보 필터 질의(이성·온보딩완료+feed·활성·본인제외, 차단 제외는 #41로 반영)
2. 최신순(`created_at desc`) + 커서 페이지네이션
3. `GET /api/discovery` + FeedCard 응답
4. 권장 인덱스, 테스트

### Phase B — 가중 점수
5. K/M/B/D 각 항목 계산 + 0~1 정규화
6. MBTI 모름 재분배, 활동성 타이브레이커
7. 합산 점수 정렬로 전환, 점수 모델 테스트

---

## 8. 의존성 / 남은 항목

| 항목 | 상태 | 비고 |
|---|---|---|
| 차단(block) 도메인 | ✅ #41 | 후보 필터의 차단/피차단 양방향 제외 반영 |
| `feed.mbti` nullable | ⏳ | "MBTI 모름" 표현·재분배에 필요(현재 NOT NULL) |
| 외형 태그 분리 | ⏳ | K 제외 대상. 현재 미구현이라 무관 |
| MBTI 궁합표 | ⏳ | 없으면 동일=1.0만 적용(궁합/100 생략) |
| 정규화 상한·다양성 임계 | 🧩 | 구현 상수로 두고 튜닝 |

---

## 부록: 원안/관련 문서
- 원안: BlurSome 마스터 설계 §17 (탐색 알고리즘)
- 키워드 점수 재료: [`KEYWORD_DOMAIN.md`](../keyword/KEYWORD_DOMAIN.md)
- 후보 프로필 소스: [`FEED_DOMAIN.md`](../feed/FEED_DOMAIN.md), 회원 상태: [`MEMBER_DOMAIN.md`](../member/MEMBER_DOMAIN.md)
- 학과/계열 점수(D): `Department`·`College` enum (이슈 #40)
- 활동성 타임스탬프: `last_active_at` (이슈 #39)
