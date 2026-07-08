# 피드 이미지 블러 파이프라인 API 테스트 가이드

BlurSome 피드 이미지 블러 파이프라인(#47~#54)을 실제 AWS(S3 + Lambda)에 붙여 **종단(end-to-end)으로 손으로
검증**하기 위한 시나리오·실행 방법 문서입니다(이슈 #55). 기능 설계는
[`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md), 도메인 규칙은
[`FEED_IMAGE_DOMAIN.md`](./FEED_IMAGE_DOMAIN.md), 인증 흐름은 [`../ARCHITECTURE.md § 8`](../ARCHITECTURE.md)를
따릅니다. 채팅 토큰·방 시드 절차는 [`../chat/CHAT_API_TEST_GUIDE.md`](../chat/CHAT_API_TEST_GUIDE.md)와 공유합니다.

> 대상 독자: 로컬/스테이징에서 업로드→블러→공개→단계공개 전 구간을 손으로 검증하려는 백엔드/QA.
> 필요 도구: `curl`(또는 Postman), MySQL/MariaDB 클라이언트, **실제 S3 버킷 2개 + Lambda 트리거**(§1-2),
> AWS 콘솔/CLI 접근 권한.

---

## 0. 한눈에 보기

핵심 모델: **사진 1장당 원본(비공개) + 블러본(공개) 2개만 존재**한다. 피드에는 블러본이 나가고, 채팅 단계가
오를수록 `displayOrder` 순서대로 **원본을 1장씩** 공개한다(단기 Presigned GET). 자세한 근거는 설계 문서 §0·§3.

| 단계 | 내용 | 이슈 |
|----|----|----|
| ① 발급 | 원본 업로드용 Presigned PUT URL 발급(blur-level을 서명에 pin) | #49 |
| ② 업로드 | 프론트가 `originals` 버킷에 원본 직접 PUT(`requiredHeaders` 그대로) | #49 |
| ③ 블러 생성 | S3 `ObjectCreated` → Lambda가 블러본 1장을 `variants`에 생성 | #54 |
| ④ 메타데이터 저장 | 발급 key로 `FeedImage` 저장(full-replace), `PROCESSING` 시작 | #50 |
| ⑤ 상태 폴링 | 스케줄러가 `HeadObject`로 블러본 존재 확인 → `READY`/`FAILED` | #51 |
| ⑥ 공개 피드 | 정확히 5장 전부 `READY`일 때만 블러본 URL 노출(전부-또는-비노출) | #52·#72 |
| ⑦ 단계 공개 | 채팅 단계가 정한 N장만 원본 Presigned GET, 나머지 블러본 | #53 |

### 테스트 대상 API 요약

모두 `Authorization: Bearer <AT>` 필요. 성공 응답은 `{ timestamp, data }`, 오류 응답은
`{ timestamp, status, message, code }` 형태입니다.

| 메서드 | 경로 | 설명 | 이슈 |
|----|----|----|----|
| POST | `/api/feeds/me/images/presigned-urls` | 원본 업로드용 Presigned PUT 발급 | #49 |
| POST | `/api/feeds/me/images` | 피드 이미지 메타데이터 저장(full-replace) | #50 |
| GET | `/api/feeds/me/images` | 본인 피드 관리 조회(PROCESSING/FAILED 포함) | #52 |
| GET | `/api/feeds/{feedId}/images` | 공개 피드 조회(정확히-5장-전부-READY 게이트, **로그인 회원이면 누구나** — 토큰 필요) | #52·#72 |
| GET | `/api/chat/rooms/{roomId}/revealed-images` | 채팅 단계별 상대 원본 공개 조회 | #53 |

> ⚠️ **업로드(②)는 백엔드가 아니라 S3로 직접 PUT**합니다. 발급 응답의 `uploadUrl`에 `requiredHeaders`를 그대로
> 실어 보내야 하며(서명 고정), 백엔드를 거치지 않습니다.

---

## 1. 사전 준비

### 1-1. 서버 실행 (local)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

- 포트 `8080`, Swagger `http://localhost:8080/swagger-ui.html`.
- `local`은 `ddl-auto: update`라 첫 실행 시 `feed`·`feed_image` 테이블이 자동 생성됩니다.
- S3 관련 환경 변수(`.env` 또는 환경 변수, IAM 역할 미사용):

| 변수 | 예시/설명 |
|----|----|
| `S3_ORIGINALS_BUCKET` | 비공개 원본 버킷명 (예: `blursome-originals`) |
| `S3_VARIANTS_BUCKET` | 공개 블러본 버킷명 (예: `blursome-variants`) |
| `S3_VARIANTS_BASE_URL` | 블러본 공개 URL 베이스. 공개 URL = `base + variantKey` (끝에 `/` 포함) |
| `S3_REGION` | `ap-northeast-2` |
| `S3_PUT_EXPIRATION` | Presigned PUT 만료(기본 `5m`) |
| `S3_GET_EXPIRATION` | Presigned GET 만료(기본 `5m`, 단계별 원본 공개용) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | IAM 사용자 자격증명(§1-2의 정책 보유) |

> 더미 placeholder 값으로도 컨텍스트는 뜨지만, **실제 PUT/GET/Lambda 검증은 진짜 버킷·자격증명이 필요**합니다.
> 더미로는 발급 URL이 만들어져도 S3가 서명을 거부합니다.

### 1-2. AWS 인프라 준비 (이슈 #55 본체)

설계 §5·§8-5 기준. **이 절을 끝내야 ③·⑤·⑦의 실제 동작을 볼 수 있습니다.** 상세 체크리스트는 부록 A.

1. **originals 버킷** — BPA 전부 ON(완전 비공개), SSE-S3, `ap-northeast-2`, CORS(PUT 허용: 프론트 도메인).
2. **variants 버킷** — BPA 해제(객체 GET만 공개), **ListBucket은 비공개**, SSE-S3, CORS(GET).
3. **IAM 사용자 정책**(서명 주체가 대상 작업 권한을 보유해야 Presigned URL이 유효):
   - originals: `s3:PutObject` + `s3:GetObject`
   - variants: 객체 `s3:GetObject` + 버킷 `s3:ListBucket`
     (`HeadObject`가 부재 객체를 **404**로 받기 위함 — 없으면 403으로 가려져 스케줄러가 `PROCESSING`에 고착)
4. **Lambda 실행 역할** — originals `s3:GetObject` + variants `s3:PutObject`.
5. **Lambda 배포 + 트리거** — originals `ObjectCreated` 이벤트 연결(#54, Python + Pillow, zip+Layer).
6. **Lifecycle** — 미완료 멀티파트 업로드 정리 우선 설정.

### 1-3. 회원·온보딩·피드·토큰 준비

피드 이미지 API는 인증 주체(`@AuthenticationPrincipal memberId`)에서 **본인 피드(회원:피드 1:1)**를 도출합니다.
`feed` 행은 온보딩 완료 트랜잭션에서 생성되므로, F-1 전에 **같은 토큰으로 온보딩을 완료**해야 합니다.

- **토큰 발급**: [`CHAT_API_TEST_GUIDE.md` §1-4](../chat/CHAT_API_TEST_GUIDE.md) 방법 A(로컬 Pre-request로 직접
  생성) 또는 방법 B(카카오 콜백)와 동일합니다. 아래 예시는 `{{accessTokenA}}`로 표기합니다.
- **학교 인증 상태**: 온보딩 API는 `VERIFIED` 회원만 통과합니다. 로컬 시드로 진행한다면 토큰의 `sub` 회원이
  `registration_status = 'VERIFIED'` 상태인지 먼저 확인하세요.
- **키워드 태그 id 확인**: 온보딩 요청의 `keywordTagIds`는 실제 DB의 태그 id를 써야 합니다. 먼저 아래 API로
  필수 카테고리(`required=true`)마다 최소 1개 태그 id를 고릅니다.

```bash
curl -X GET "{{baseUrl}}/api/keywords" \
  -H "Authorization: Bearer {{accessTokenA}}"
```

> 로컬 기본 seed를 그대로 썼다면 예시로 `HOBBY`, `PERSONALITY`, `CONTACT`에서 각각 1개 이상 선택하면 됩니다.
> DB를 직접 확인하려면 `SELECT id, code, category_id FROM keyword_tag ORDER BY id;`를 사용하세요.

---

### 1-4. 온보딩 완료로 피드 생성

F-1 전에 한 번 실행합니다. 성공하면 응답의 `data.feedId`(또는 DB의 `feed.id`)가 공개 조회(F-6)에 사용할
`feedId`입니다.

- **요청**: `POST {{baseUrl}}/api/members/me/onboarding`

```json
{
  "nickName": "테스트A",
  "birthYear": 2000,
  "department": "ENGLISH",
  "mbti": "ISTJ",
  "gender": "MALE",
  "keywordTagIds": [1, 26, 45]
}
```

- **기대**: `200`. 회원 상태가 `COMPLETED`가 되고, 같은 트랜잭션에서 `feed`가 생성됩니다.

```sql
SELECT m.id, m.nick_name, m.registration_status, f.id AS feed_id
FROM member m
LEFT JOIN feed f ON f.member_id = m.id
WHERE m.id = <토큰의 memberId>;
```

> F-3에서 `FEED_404_NOT_FOUND`가 나오면 대부분 이 단계가 누락됐거나, 온보딩한 토큰과 F-1~F-3에 쓰는 토큰이
> 서로 다른 경우입니다. F-1 응답의 `originalKey`가 `originals/{memberId}/...` 형태이므로 온보딩, 발급, 저장은
> 모두 같은 토큰으로 진행해야 합니다.

---

## 2. REST 테스트 시나리오

`{{baseUrl}}` = `http://localhost:8080`. 헤더 `Authorization: Bearer {{accessTokenA}}`.

### 시나리오 F-1. Presigned PUT 발급 (#49)

- **요청**: `POST {{baseUrl}}/api/feeds/me/images/presigned-urls`

```json
{
  "images": [
    { "fileName": "first.jpg",  "contentType": "image/jpeg", "blurLevel": 70 },
    { "fileName": "second.png", "contentType": "image/png" }
  ]
}
```

- **기대**: `200`. 사진별 `uploadUrl`·`originalKey`(=`originals/{memberId}/{uuid}.{ext}`)·`requiredHeaders`.

```json
{
  "timestamp": "2026-06-25T12:00:00.000+09:00",
  "data": {
    "images": [
      {
        "uploadUrl": "https://blursome-originals.s3.ap-northeast-2.amazonaws.com/originals/1001/<uuid>.jpg?X-Amz-...",
        "originalKey": "originals/1001/<uuid>.jpg",
        "requiredHeaders": { "Content-Type": "image/jpeg", "x-amz-meta-blur-level": "70" }
      },
      {
        "uploadUrl": "https://.../originals/1001/<uuid>.png?X-Amz-...",
        "originalKey": "originals/1001/<uuid>.png",
        "requiredHeaders": { "Content-Type": "image/png", "x-amz-meta-blur-level": "80" }
      }
    ]
  }
}
```

- `blurLevel` 생략 시 기본값 **80**이 `requiredHeaders`에 박힙니다.
- **검증 포인트**: `originalKey`가 `originals/{내 memberId}/` prefix인지, `requiredHeaders`에
  `x-amz-meta-blur-level`이 있는지(서명 pin).
- **오류**: `blurLevel`이 49 이하/101 이상 → `400`. `contentType`이 `image/*`가 아님 → `400`. 6장 이상 → `400`.

### 시나리오 F-2. 원본을 S3로 직접 PUT (#49)

발급받은 `uploadUrl`에 `requiredHeaders`를 **그대로** 실어 PUT합니다(백엔드 미경유).

```bash
curl -X PUT "<uploadUrl>" \
  -H "Content-Type: image/jpeg" \
  -H "x-amz-meta-blur-level: 70" \
  --data-binary "@first.jpg"
```

- **기대**: `200 OK`(S3). 헤더가 하나라도 누락/변경되면 **서명 불일치로 403**.
- 이 PUT의 `ObjectCreated` 이벤트가 Lambda(#54)를 트리거해 잠시 후 `variants/{memberId}/{uuid}.jpg` 블러본이
  생깁니다(③). AWS 콘솔에서 variants 버킷에 객체가 생기는지 확인하세요.

### 시나리오 F-3. 메타데이터 저장 — full-replace (#50)

S3 업로드를 마친 뒤, 발급 응답의 `originalKey`를 그대로 보냅니다.

- **요청**: `POST {{baseUrl}}/api/feeds/me/images`

```json
{
  "images": [
    { "objectKey": "originals/1001/<uuid-1>.jpg", "displayOrder": 1, "blurLevel": 70 },
    { "objectKey": "originals/1001/<uuid-2>.png", "displayOrder": 2 }
  ]
}
```

- **기대**: `200`. 저장된 사진을 `displayOrder` 순서로 반환, 상태는 `PROCESSING`(블러본 비동기 생성 중).

```json
{
  "timestamp": "...",
  "data": {
    "images": [
      { "id": 1, "displayOrder": 1, "variantUrl": "https://.../variants/1001/<uuid-1>.jpg", "blurLevel": 70, "processingStatus": "PROCESSING" },
      { "id": 2, "displayOrder": 2, "variantUrl": "https://.../variants/1001/<uuid-2>.jpg", "blurLevel": 80, "processingStatus": "PROCESSING" }
    ]
  }
}
```

- **full-replace**: 같은 회원으로 다시 호출하면 기존 행을 대체합니다(추가·삭제·재정렬을 한 요청으로).
- **오류**: `displayOrder` 중복 → `400 FEED_IMAGE_400_DUPLICATE_ORDER`. 다른 회원 prefix/형식 위반 key →
  `400 FEED_IMAGE_400_INVALID_OBJECT_KEY`. 피드 없음 → `404 FEED_404_NOT_FOUND`.

### 시나리오 F-4. 본인 피드 관리 조회 (#52, PROCESSING 확인)

- **요청**: `GET {{baseUrl}}/api/feeds/me/images` — A 토큰
- **기대**: `200`. `PROCESSING`/`FAILED`도 포함해 진행 상태를 보여줍니다.

```json
{ "timestamp": "...", "data": { "reuploadRequired": false, "images": [
  { "id": 1, "displayOrder": 1, "variantUrl": "https://.../variants/1001/<uuid-1>.jpg", "blurLevel": 70, "processingStatus": "PROCESSING" }
] } }
```

- 블러본 생성이 실패해 `FAILED`가 하나라도 있으면 `reuploadRequired: true`(해당 사진 재업로드 안내).

### 시나리오 F-5. 상태 폴링 → READY 전이 관찰 (#51)

스케줄러가 **1분마다** `PROCESSING`이면서 생성 30초 경과한 행을 `HeadObject`로 확인합니다.

- 블러본이 정상 생성됐다면(③ 성공) **약 1~2분 내** 상태가 `READY`로 바뀝니다.
- 확인: F-4(본인 조회)를 반복 호출해 `processingStatus`가 `PROCESSING → READY`로 변하는지 보거나, DB
  `SELECT processing_status FROM feed_image WHERE feed_id = 1;`로 확인.
- **FAILED 경로**: 블러본이 끝내 안 생기면(트리거/권한 문제) `created_at + 5분` 초과 시 `FAILED`로 전이됩니다.
  이때 F-4의 `reuploadRequired`가 `true`가 됩니다. → 부록 A의 IAM `ListBucket` 항목을 우선 점검.

### 시나리오 F-6. 공개 피드 조회 (#52·#72, 정확히-5장-전부-READY 게이트)

- **요청**: `GET {{baseUrl}}/api/feeds/1/images` — **토큰 필요**(로그인한 회원이면 누구나 남의 피드를 조회할 수 있고,
  소유권 검증은 없음). 토큰 없이 호출하면 `401`이다.
- **기대(정확히 5장 전부 READY)**: `200`, 블러본 URL을 `displayOrder` 순서로(5장).

```json
{ "timestamp": "...", "data": { "images": [
  { "displayOrder": 1, "variantUrl": "https://.../variants/1001/<uuid-1>.jpg" },
  { "displayOrder": 2, "variantUrl": "https://.../variants/1001/<uuid-2>.jpg" }
] } }
```

- **기대(게이트 미통과)**: 사진이 **5장 미만/초과**거나, 5장이라도 하나라도 `PROCESSING`/`FAILED`이거나,
  이미지가 0장이면 → `404 FEED_404_NOT_FOUND`(깨진/불완전 블러본 노출 방지, 전부-또는-비노출, #72).
  같은 규칙이 탐색 후보 노출에도 적용되어, 5장 전부 `READY`가 되기 전에는 상대 탐색 목록에 뜨지 않는다.
- `variantUrl`을 브라우저로 직접 열어 블러본 이미지가 보이는지(공개 GET) 확인하세요.

### 시나리오 F-7. 채팅 단계별 원본 공개 (#53)

상대(A)의 피드 사진을, 채팅 단계가 오를수록 `displayOrder` 순서로 원본 1장씩 공개합니다. **요청자(B)는 방
참여자**여야 하고, 공개 대상은 **상대(A)의 사진**입니다.

전제: [`CHAT_API_TEST_GUIDE.md` §1-2](../chat/CHAT_API_TEST_GUIDE.md)로 A(1001)·B(1002)와 ACTIVE 방(roomId=1)을
시드하고, A의 피드 사진이 **전부 READY**(F-1~F-6)인 상태.

- **요청**: `GET {{baseUrl}}/api/chat/rooms/1/revealed-images` — **B 토큰**

1. **MATCHED(공개 0장)**: 전부 블러본.

```json
{ "timestamp": "...", "data": { "images": [
  { "displayOrder": 1, "revealed": false, "imageUrl": "https://.../variants/1001/<uuid-1>.jpg" },
  { "displayOrder": 2, "revealed": false, "imageUrl": "https://.../variants/1001/<uuid-2>.jpg" }
] } }
```

2. **단계 상승**: 채팅 유효 메시지 누적(A·B가 교대로 유효 TEXT를 주고받아 양방향 최소가 임계값 10/20/…을 넘김)으로
   단계를 `PHOTO_REVEAL_STEP_1`(공개 1장) → `STEP_2`(2장)…로 올립니다([채팅 가이드 R-4](../chat/CHAT_API_TEST_GUIDE.md)).
   손으로 임계값까지 채우기 어렵다면 DB로 `UPDATE chat_room SET progress_status = 'PHOTO_REVEAL_STEP_2' WHERE id = 1;`로 강제할 수 있습니다.

3. **STEP_2에서 재조회**: 앞 2장은 `revealed: true` + **원본 단기 Presigned GET URL**, 나머지는 블러본.

```json
{ "timestamp": "...", "data": { "images": [
  { "displayOrder": 1, "revealed": true,  "imageUrl": "https://blursome-originals.s3...amazonaws.com/originals/1001/<uuid-1>.jpg?X-Amz-..." },
  { "displayOrder": 2, "revealed": true,  "imageUrl": "https://.../originals/1001/<uuid-2>.png?X-Amz-..." },
  { "displayOrder": 3, "revealed": false, "imageUrl": "https://.../variants/1001/<uuid-3>.jpg" }
] } }
```

- **검증 포인트**: `revealed: true`의 `imageUrl`은 `originals` 버킷의 **서명된(X-Amz-...) 단기 URL**입니다.
  브라우저로 열면 5분 내에는 원본이 보이고, 5분 뒤에는 만료로 막힙니다. 서명 없이 originals 객체를 직접 열면
  접근 불가(비공개 버킷)임을 함께 확인하세요.
- **공개 장수 캡**: 사진이 N보다 적으면 보유 수만큼만 공개됩니다(`min(단계 장수, 보유 사진 수)`).
- **오류**: 비참여자 → `403 CHAT_403_NOT_PARTICIPANT`. 방 없음/내가 나감 → `404 CHAT_404_ROOM_NOT_FOUND`.
  상대가 나가 **종료(CLOSED)된 방** → `409 CHAT_409_ROOM_CLOSED`(원본 공개는 메시지 이력보다 민감해 종료 방에선
  발급하지 않음).

### REST 오류 케이스 정리

| 상황 | HTTP | code |
|----|----|----|
| blurLevel 범위 밖 / contentType 비이미지 / 6장 이상 / 0장 저장 | 400 | (Bean Validation 메시지) |
| displayOrder 중복 | 400 | `FEED_IMAGE_400_DUPLICATE_ORDER` |
| 다른 회원 prefix·형식 위반 objectKey | 400 | `FEED_IMAGE_400_INVALID_OBJECT_KEY` |
| 피드 없음(저장/본인 조회) / 미완·0장 공개 피드 | 404 | `FEED_404_NOT_FOUND` |
| 단계 조회: 비참여자 | 403 | `CHAT_403_NOT_PARTICIPANT` |
| 단계 조회: 방 없음/내가 나간 방 | 404 | `CHAT_404_ROOM_NOT_FOUND` |
| 단계 조회: 종료(CLOSED)된 방 | 409 | `CHAT_409_ROOM_CLOSED` |
| 토큰 없음/만료/위조 | 401 | `JWT_401_UNAUTHORIZED` / `JWT_401_EXPIRED` / `JWT_401_INVALID` |

---

## 3. 통합(E2E) 시나리오 — 추천 순서

1. §1-2 AWS 인프라 준비(버킷·IAM·Lambda 트리거) → §1-3 회원·토큰 준비 → §1-4 온보딩 완료.
2. **F-1** 발급 → **F-2** S3 PUT(2장) → AWS 콘솔에서 variants에 블러본 생성 확인(③).
3. **F-3** 메타데이터 저장(`PROCESSING`) → **F-4** 본인 조회로 `PROCESSING` 확인.
4. 1~2분 대기 후 **F-5/F-4**로 `READY` 전이 확인.
5. **F-6** 공개 피드가 `200`으로 블러본을 노출하는지(전부 READY일 때만) 확인.
6. 채팅 방 시드 → **F-7** 단계별 원본 공개: MATCHED(전부 블러) → 단계 상승 → 앞 N장 원본 GET 확인.
7. 오류 케이스(오류표) 점검: 미완 상태 공개 피드 404, 종료된 방 단계 조회 409 등.

> ⚠️ 실제 비용/노출 주의: originals는 절대 공개되면 안 됩니다(부록 A에서 BPA가 전부 ON인지 재확인). 단계별
> 원본 URL은 단기(5분)라 만료 후 재발급됩니다.

---

## 4. 트러블슈팅

| 증상 | 원인/해결 |
|----|----|
| S3 PUT이 `403 SignatureDoesNotMatch` | `requiredHeaders`(`Content-Type`·`x-amz-meta-blur-level`)를 그대로 안 보냄. 값/철자/대소문자 그대로 전송 |
| 메타데이터 저장이 `FEED_IMAGE_400_INVALID_OBJECT_KEY` | 발급받은 `originalKey`를 그대로 안 보냄(다른 회원 prefix·임의 경로). F-1 응답 값 그대로 사용 |
| 상태가 영원히 `PROCESSING` | 블러본 미생성(트리거 미연결) **또는** variants `s3:ListBucket` 미부여로 부재 객체가 403으로 가려짐(404 필요). 부록 A IAM 점검 |
| 상태가 `FAILED` | `created_at+5분`까지 블러본 미확인. Lambda 로그(CloudWatch)·트리거·실행 역할 권한 확인 후 해당 사진 재업로드 |
| 공개 피드가 계속 `404` | 일부가 아직 `PROCESSING`/`FAILED`이거나 0장(전부-또는-비노출 게이트). F-4로 전 상태 확인 |
| 단계 조회가 전부 `revealed:false` | 방 단계가 아직 `MATCHED`이거나, 미공개 사진의 블러본이 `READY`가 아니어서 제외됨. 단계 상승·READY 확인 |
| 단계 조회가 `409 ROOM_CLOSED` | 상대가 나가 방이 종료됨(원본 공개 차단 정책). 새 방을 시드 |
| 원본 GET URL이 `403` | 만료(5분 초과) 또는 서명 누락. 재조회로 재발급. 서명 없이 originals 직접 접근은 정상적으로 차단됨 |

---

## 부록 A. AWS 인프라 구성 체크리스트 (이슈 #55)

설계 §5·§8-4·§8-5 기준. 콘솔 작업 후 아래를 확인합니다.

### A-1. 버킷

| 설정 | originals | variants |
|----|----|----|
| 리전 | ap-northeast-2 | ap-northeast-2 |
| 퍼블릭 액세스 차단(BPA) | **전부 ON(완전 비공개)** | **해제(객체 GET만 공개)** |
| ListBucket(목록) | 비공개 | **비공개(절대 공개 X)** |
| 암호화 | SSE-S3 | SSE-S3 |
| 버전 관리 | 비활성화 | 비활성화 |
| CORS | PUT(프론트 도메인) | 필요 시 GET |

> ⚠️ originals의 BPA를 한 번이라도 풀었다면 **전부 ON으로 되돌릴 것**. 원본은 절대 공개되면 안 됩니다.

### A-2. IAM (운영 서버 사용자)

```jsonc
// 최소 권한: 리소스 ARN을 각 버킷/prefix로 제한
{
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::blursome-originals/*" },
    { "Effect": "Allow", "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::blursome-variants/*" },
    // HeadObject가 부재 객체를 404로 받기 위해 버킷 ARN에 ListBucket 부여 (미부여 시 403 → PROCESSING 고착)
    { "Effect": "Allow", "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::blursome-variants" }
  ]
}
```

- 이 `ListBucket`은 **백엔드 IAM에만** 부여하며, 퍼블릭/버킷 정책의 "ListBucket 비공개"와 별개입니다(§5·§8-5).
- 발급한 access/secret key는 운영 서버 `.env`(`S3_ACCESS_KEY`/`S3_SECRET_KEY`)에 주입(IAM 역할 미사용).

### A-3. Lambda

- 실행 역할: originals `s3:GetObject` + variants `s3:PutObject`.
- 트리거: originals `ObjectCreated`.
- 런타임/배포: Python 3.x + Pillow, **zip + Layer**(컨테이너 이미지 미사용), **VPC 미연결**(NAT 비용 회피).
- 블러: 다운스케일 → Gaussian(`blur_level` 반영) → 업스케일 → jpg 저장(`variants/{memberId}/{uuid}.jpg`).

### A-4. Lifecycle

- v1: 미완료 멀티파트 업로드 정리 우선. 고아 객체(full-replace로 빠진 originals/variants)는 후속 cleanup batch
  또는 prefix 정책으로 7~30일 후 삭제(§8-4).

---

## 부록 B. 자동화된 통합 테스트와의 관계

이 가이드의 **백엔드 측 로직**(발급·저장·폴링 전이·공개 게이트·단계 공개)은
`src/test/java/.../feed/FeedImagePipelineIntegrationTest.java`가 실제 MySQL(Testcontainers) + 실제 서비스/
스케줄러로 검증합니다. 단, AWS와 닿는 경계(`S3Client`/`S3Presigner`)는 목으로 대체하므로 **실제 버킷·IAM·Lambda
동작은 이 문서의 수동 절차로만 확인**됩니다. 즉:

- 자동 테스트가 보장: 버킷 선택·blur-level pin·키 규칙·상태 전이·전부-READY 게이트·단계별 N장 공개 로직.
- 수동 테스트가 보장: 실제 Presigned PUT/GET 서명 유효성, Lambda 트리거·블러 생성, BPA/IAM/CORS 설정.

```bash
# 자동 통합 테스트만 실행 (Docker 필요)
./gradlew test --tests "com.blursome.blursome.feed.FeedImagePipelineIntegrationTest"
```

---

## 참고

- 파이프라인 설계: [`docs/feed/FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §0·§3·§4·§5·§8
- 도메인 규칙: [`docs/feed/FEED_IMAGE_DOMAIN.md`](./FEED_IMAGE_DOMAIN.md)
- 채팅 토큰·방 시드: [`docs/chat/CHAT_API_TEST_GUIDE.md`](../chat/CHAT_API_TEST_GUIDE.md)
- 에러 코드: `com.blursome.blursome.feed.exception.FeedErrorCode`·`FeedImageErrorCode`,
  `com.blursome.blursome.chat.exception.ChatErrorCode`
