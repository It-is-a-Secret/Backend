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
| 피드 공개 가능 | `feed_image`가 **정확히 5장이고 전부 `READY`** — ✅ 구현(#72) |
| 이미 채팅 중(`ACTIVE`) | **포함하되 목록 뒤쪽**, 카드에 `chatRoom` 표시 — ✅ 구현(#112) |
| 다시 시작할 수 없는 관계 | viewer와의 방이 `CLOSED`(관계 영구 종료)·`REPORTED`(검토 대기)면 **제외** — ✅ 구현(#112) |

> ✅ **#41(차단 도메인) 반영**: 후보 쿼리에 `not exists (block where 양방향)`을 추가해 차단/피차단을 제외한다.
>
> ✅ **#72(공개 게이트) 반영**: 온보딩이 `COMPLETED`여도 이미지 파이프라인을 통과하지 못한 피드(0장·`PROCESSING`/`FAILED` 혼입)는 깨진 블러본 노출을 막기 위해 제외한다. 후보 쿼리에 `READY` 개수 = 5(`정확히 5장`)와 비-`READY` 부재(`not exists`)를 함께 강제해 `FeedImage.isPublishable`(공개 피드 조회 게이트)과 동일한 규칙으로 맞춘다. `feed_image(feed_id, processing_status)` 보조 인덱스로 서브쿼리를 뒷받침한다.

### 2-1. 기존 대화 관계 반영 ✅ (#112)

서버는 회원 쌍당 방을 하나로 보장하지만(`chat_room.pair_key` 유니크), 예전 탐색 응답에는 관계 정보가 없었다. 그래서 이미 대화 중인 상대에게도 첫 메시지 입력 UI가 열렸고, 그 메시지는 저장되지 않았다(`created=false`). 다시 시작할 수 없는 상대도 탐색 상단에 계속 나왔다.

| viewer와의 방 상태 | 탐색 노출 | 카드 `chatRoom` | 클릭 시 |
|---|---|---|---|
| 없음 | 노출 | `null` | 첫 메시지 입력 → `POST /api/discovery/feeds/{feedId}/chat` |
| `ACTIVE` | 노출, **목록 뒤쪽** | `{ roomId, roomStatus: ACTIVE }` | 첫 메시지 없이 `roomId`로 기존 방 진입 |
| `BLOCKED` | 제외 (차단 필터) | — | — |
| `REPORTED` | **제외** | — | — |
| `CLOSED` | **제외** (viewer가 나간 방 포함) | — | — |

- **제외는 DB 단계에서 한다.** 점수 계산·페이지 자르기 전에 빼야 후보 상한(500)이 실제 노출 가능한 후보로 채워지고 페이지 경계가 흔들리지 않는다. 후보 쿼리에 `not exists (viewer 멤버십 ⋈ 같은 방의 후보 멤버십, 방 상태 in (CLOSED, REPORTED))`를 추가했다. `pair_key` 문자열을 조립하는 조인은 인덱스를 쓰기 어려워, viewer 멤버십(`idx_chat_room_member_member`)에서 같은 방의 상대 행으로 조인한다.
- **viewer가 나간 방도 판정에 넣는다.** 채팅 목록용 쿼리(`findActiveMembershipsWithRoom` 등)는 `left_at IS NULL`만 보므로 재사용하지 않는다. 재사용하면 viewer가 나가 종료된 방의 상대가 "관계 없음"으로 보이는데 대화 시작은 409가 된다.
- **표시·정렬용 관계는 viewer 기준 쿼리 1회로 읽는다.** `ChatRoomService.findPartnerRooms(viewerId)`가 viewer가 참여했던 모든 방의 `상대 id → (roomId, roomStatus)`를 돌려준다. 비용은 후보 수(N≤500)가 아니라 viewer의 방 수에 비례한다. 조회 사이에 상태가 바뀐 경우를 대비해 앱에서도 `CLOSED`·`REPORTED`를 한 번 더 거른다.
- **정렬 키**: `ACTIVE 방 없음 우선 → 점수 desc → 최근 7일 접속 → last_active_at desc → feedId desc`. 탐색의 목적이 새 상대 발견이므로 이미 대화 중인 상대는 점수와 무관하게 뒤로 보내되, 카드에서 기존 방으로 가는 진입점은 남긴다. 비교 키가 하나 늘 뿐이라 정렬 비용은 그대로 O(N log N)다.
- **카드 상세**(`GET /api/discovery/feeds/{feedId}`)도 같은 기준이다. 페어 키 유니크 인덱스로 방을 단건 조회해 `CLOSED`·`REPORTED`면 404, `ACTIVE`면 `chatRoom`을 내린다.
- **대화 시작 API 계약은 유지한다.** `ACTIVE`면 보낸 메시지를 저장하지 않고 `created=false`로 기존 방을 안내한다. 카드에 `chatRoom`이 있으면 정상 흐름에서는 이 분기를 타지 않는다. 시작 API는 첫 메시지가 필수(`@NotBlank`)라 기존 방 입장 용도로 쓸 수 없다.
- **알려진 한계**: offset 페이지는 매 요청 재계산이므로, 페이지를 넘기는 사이에 새 대화를 시작하면 그 상대가 뒤로 이동해 다음 페이지에서 다시 보이거나 다른 항목이 한 칸 당겨질 수 있다. 필요해지면 점수 스냅샷 커서를 검토한다.

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

> ✅ **`feed.mbti` nullable 적용(#76)**: `feed.mbti`를 nullable로 두고 온보딩에서 "모름"(null) 입력을 허용한다. viewer/후보 중 하나라도 `mbti=null`이면 M을 빼고 30%를 K/B/D에 재분배한다(위 표). 점수 로직(`DiscoveryScorer`)은 null을 직접 판정한다.

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
- **카드 상세 (✅ 구현, #95)**: `GET /api/discovery/feeds/{feedId}` → `{ feedId, nickName, birthYear, department, departmentLabel, mbti, gender, keywords[{ categoryCode, categoryName, tagId, tagCode, tagName, matched }] }`. `matched`는 조회자도 같은 태그를 선택했는지 여부다. 사진은 `GET /api/feeds/{feedId}/images`로 따로 받는다. feedId 직접 입력으로 후보 필터(§2)를 우회하지 못하도록 같은 조건(이성·활성·온보딩 완료·차단 양방향 아님·사진 5장 READY)을 단건으로 재검증하고, 미충족은 존재 여부를 드러내지 않도록 없음과 같은 `404 DISCOVERY_404_FEED_NOT_FOUND`로 응답한다. 조회자 온보딩 전이면 `403 DISCOVERY_403_ONBOARDING_REQUIRED`.
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
| 피드 공개 게이트 | ✅ #72 | 후보 필터에 "정확히 5장 전부 READY"(`feed_image`) 추가, `FeedImage.isPublishable`과 동일 규칙 |
| `feed.mbti` nullable | ✅ #76 | "MBTI 모름"(null) 표현·재분배 활성화. 온보딩 mbti 선택값 |
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
