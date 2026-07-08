# 피드 이미지 블러 파이프라인 설계 결정 (팀 공유용)

BlurSome의 핵심 메커니즘인 **단계별 사진 공개**를 안전하게 구현하기 위한 이미지 적재·블러·전달 구조를 확정한다. 본 문서는 결정 사항을 팀원에게 전달하기 위한 것이다.

> 상태 표기
> - ✅ **확정**: 의사결정 완료, 본 구조의 기준
> - 🧩 **설계(제안)**: 방향은 정했으나 세부 보강 예정
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

관련 문서: [`FEED_IMAGE_DOMAIN.md`](./FEED_IMAGE_DOMAIN.md), [`ARCHITECTURE.md`](../ARCHITECTURE.md)

---

## 0. 한 줄 요약 (먼저 읽을 것)

> **사진 1장당 원본(비공개) + 블러본(공개) 2장만 존재한다. 피드에는 블러본이 나가고, 채팅에서 공개 단계가 오를수록 `displayOrder` 순서대로 원본을 1장씩 공개한다.**

이 모델은 "흐림 강도를 점점 푸는" 블러 사다리(s1/s2/s3)가 **아니다.** 블러본은 사진당 **1장으로 고정**이고, "점진적 공개"는 **어떤 사진의 원본을 공개했는가**(장수)로 표현된다.

- **피드 조회**: 모든 사진은 항상 **블러본(공개)** 으로 노출된다.
- **채팅방**: `progressStatus`가 오를수록 1번 사진 원본 → 2번 사진 원본 → … 순으로 **원본을 1장씩** 공개한다. 아직 공개되지 않은 사진은 채팅에서도 블러본으로 보인다.
- 원본은 **비공개 버킷**에 있고, 채팅 단계 조건을 충족한 참여자에게만 **단기 Presigned GET**으로 전달된다.

### 왜 블러를 서버가 굽나

CSS/canvas 블러는 클라이언트 화면 효과일 뿐이라 DevTools로 `filter` 제거, Network 탭에서 원본 URL 직접 열기 등으로 1초 만에 우회된다. 즉 **원본이 클라이언트에 도달하는 순간 블러는 무의미**하다. 본 모델에서는 두 겹으로 막는다.

1. **원본을 분리·게이팅**: 원본은 비공개 버킷에 두고, 채팅 단계를 충족한 참여자에게만 단기 Presigned GET으로 내려간다. 공개되기 전에는 어떤 클라이언트에도 원본 바이트가 도달하지 않는다.
2. **블러본은 서버가 생성**: 피드에 나가는 블러본을 서버(Lambda)가 픽셀에 구워, 복원 불가능하게 만든다.

> ℹ️ 원본이 (1)로 독립 게이팅되므로 "블러를 클라가 만들면 우회된다"는 근거는 약해진다(블러본은 어차피 공개 teaser일 뿐, 약하게 흐려도 원본은 안 샌다). 그럼에도 **블러본 품질·일관성을 서버가 보장**하고 업로드를 원본 1장으로 단순화하기 위해 **서버사이드(Lambda) 생성을 선택**했다.

---

## 1. 핵심 결정 사항

| # | 항목 | 결정 | 상태 |
|---|---|---|---|
| 1 | 버킷 분리 | **originals(비공개) / variants(공개)** 2개 버킷으로 분리 | ✅ |
| 2 | 분리 근거 | 퍼블릭 액세스 차단(BPA)이 **버킷 단위** 설정이라, 한 버킷 내 prefix별로 공개/비공개를 못 가름 | ✅ |
| 3 | 변형본 개수 | 사진 1장당 **블러본 1장** (사다리 없음) | ✅ |
| 4 | 블러 생성 위치 | **서버사이드(Lambda)**. 프론트 CSS 블러 금지 | ✅ |
| 5 | 프론트 업로드 | **원본 1장만** originals에 Presigned PUT으로 적재 | ✅ |
| 6 | 블러 연산 방식 | **다운스케일 + 강한 가우시안** (복원 불가하게) | ✅ |
| 7 | Lambda 역할 | **S3 in → S3 out 무상태 함수.** DB 직접 접근 안 함 (B 방식) | ✅ |
| 8 | 매핑 기록 주체 | **백엔드**가 원본↔블러본 키를 DB에 저장(결정론적 키라 계산 가능) | ✅ |
| 9 | 피드 노출 | **블러본(공개 GET URL)**. 원본은 피드에 절대 노출 안 함 | ✅ |
| 10 | 단계 공개 단위 | `progressStatus`가 오를수록 **`displayOrder` 순서대로 원본 1장씩** | ✅ |
| 11 | 원본 전달 | 채팅 조건 충족 시 **단기 Presigned GET** 발급. CloudFront 미사용 | ✅ |
| 12 | variants 공개 범위 | 객체 **GET만 공개**, ListBucket(목록) 절대 비공개 | ✅ |
| 13 | originals 공개 범위 | **항상 완전 비공개** (BPA 전부 ON) | ✅ |
| 14 | 블러 강도 지정 | `blur_level`(프론트가 지정하는 **Lambda 블러 강도값**). 원본 S3 객체 메타데이터로 Lambda에 전달 | ✅ |

---

## 2. 데이터 흐름

```
① 업로드 (프론트 → originals, 비공개)
   FE: 리사이징/비율 조정만 수행 (블러 X)
   FE → BE   Presigned PUT URL 요청 (원본 1장 + 원하는 blur_level)
   BE: blur_level을 원본 객체 메타데이터(x-amz-meta-blur-level)로 박아 PUT 서명
   FE → S3   originals/{memberId}/{uuid}.{ext} 에 원본 PUT (메타데이터 포함)
                    │  ObjectCreated 이벤트
                    ▼
② 블러 생성 (Lambda, S3 in→out)
   원본 + 객체 메타데이터(blur_level) 읽기
   → 다운스케일 + 가우시안(강도 = blur_level)으로 블러본 1장 생성
   → variants/{memberId}/{uuid}.jpg 에 기록
   (Lambda는 DB를 모름. blur_level은 객체 메타데이터로 받고, 키 규칙이 결정론적이라 통지 불필요)
                    │
                    ▼
③ 매핑 저장 (백엔드)
   결정론적 키 규칙으로 original_key·variant_key를 계산해 DB(FeedImage)에 기록
   status = PROCESSING
                    │
                    ▼
③-b 상태 폴링 (백엔드 스케줄러, B 방식 유지)
   @Scheduled 1분마다, status = PROCESSING 행 중
   created_at <= now - 30초 인 것만, created_at ASC, 최대 100개
   → 각 행의 variant_key 에 대해 variants 버킷 HeadObject
   ├── 200 OK                       → status = READY
   └── 404 && created_at <= now-5분 → status = FAILED
   (404 + 5분 이내 → 그대로 PROCESSING 유지, 다음 주기 재확인)

④-a 피드 조회 (항상 블러본)
   공개 피드: 현재 DB 목록의 모든 이미지가 READY일 때만 피드 노출
             (FAILED/PROCESSING이 하나라도 있으면 비노출)
   본인 관리 조회: PROCESSING/FAILED도 함께 내려 진행/실패 노출
             FAILED가 하나라도 있으면 해당 사진 재업로드 안내

④-b 채팅 단계 공개 (조건 충족 시, 원본 1장씩)
   BE: progressStatus → 공개 장수 N 산출 + 방 참여자 여부 검증
   → displayOrder 1..N 사진의 originals 단기 Presigned GET 발급 (만료 예: 5분)
   → 나머지(N+1..) 사진은 채팅에서도 블러본 제공
   FE → S3   해당 URL로 직접 GET (비공개 버킷이지만 서명으로 접근)
```

### 키 레이아웃 (✅)

```
originals/{memberId}/{uuid}.{ext}    ← 비공개. 채팅 단계 충족 시 Presigned GET으로만 전달
variants/{memberId}/{uuid}.jpg       ← 공개(GET). 블러본 1장. 피드 노출
```

> 블러본 키를 **원본 uuid 기반 결정론적 규칙**으로 정하면, 백엔드가 Lambda의 통지 없이도 블러본 경로를 계산해 DB에 넣을 수 있다(B 방식의 핵심).
>
> **이전 모델 대비 보안 단순화**: 블러본이 단 1종이므로, 예전 사다리 모델(s1/s2/s3 공개+결정론적)에서 있던 "파일명만 바꿔 다음 단계를 미리 본다"는 우회가 **원천적으로 불가능**하다. 공개되는 건 블러본 하나뿐이고, 점진적으로 풀리는 원본은 비공개+Presigned GET으로만 나간다.

### blur_level 전달 (✅ 확정)

`blur_level`은 **프론트가 사진별로 지정하는 Lambda 블러 강도값**이다(범위 50~100, 50이 흐림 하한). Lambda가 DB를 모르는 무상태 함수(B 방식)이므로, 값은 **원본 S3 객체의 사용자 정의 메타데이터로 전달**한다. **키 인코딩(`uuid_b80.jpg` 등)은 사용하지 않는다.**

1. 백엔드는 Presigned PUT 발급 요청에서 `blurLevel`을 받는다.
2. 백엔드는 `blurLevel`을 **`50~100` 범위로 검증**한다.
3. 백엔드는 `PutObjectRequest.metadata("blur-level", value)`를 포함해 Presigned PUT URL을 생성한다(서명에 메타데이터가 고정됨).
4. 응답으로 발급 URL과 함께 **`requiredHeaders`**(반드시 보내야 하는 헤더 맵)를 내려준다.
5. 프론트는 `requiredHeaders`를 **그대로** 실어 S3에 PUT한다. 실제 PUT에는 `x-amz-meta-blur-level: {value}` 헤더가 포함되어야 한다(누락 시 서명 불일치로 PUT 실패).
6. Lambda는 원본 객체의 metadata에서 `blur-level`을 읽어 블러 강도로 사용한다.
7. 백엔드는 메타데이터 저장 단계(③)에서 **같은 값을 `FeedImage.blurLevel`에도 저장**한다(피드 응답·재생성 참고용).

> **메타데이터 키 표기**: AWS SDK에서 `metadata("blur-level", ...)`로 넣으면 실제 객체에는 `x-amz-meta-blur-level`로 저장된다. Lambda는 SDK로 읽을 때 `blur-level` 키로 접근한다.
>
> **검증·보안**: `blur_level`은 클라이언트가 지정하지만 흐림 하한(`@Min(50)`)을 DTO·서버에서 강제하므로, 약하게 굽도록 골라도 일정 수준 이상은 보장된다. 원본 자체는 비공개+게이팅이라 `blur_level`은 공개 teaser(블러본)의 흐림 정도만 조절한다. 메타데이터가 서명에 pin되어 있어 프론트가 `blurLevel`을 임의로 바꿔 PUT하면 서명이 깨진다.

---

## 3. progressStatus ↔ 원본 공개 장수 매핑 (✅ 확정)

채팅방의 `ChatProgressStatus`(chat 도메인 소유)가 **공개할 원본 장수**(`revealedOriginalCount`, 0~5)를 직접 보유한다. 사진은 `displayOrder`(1~5) 순서대로 풀린다.

| ChatProgressStatus | revealedOriginalCount | 공개되는 원본 | 채팅방 표시 |
|---|---|---|---|
| `MATCHED` | 0 | 없음 | 전부 블러본 |
| `PHOTO_REVEAL_STEP_1` | 1 | 1번 | 1번 원본 + 2~5 블러 |
| `PHOTO_REVEAL_STEP_2` | 2 | 1~2번 | 1~2번 원본 + 3~5 블러 |
| `PHOTO_REVEAL_STEP_3` | 3 | 1~3번 | 1~3번 원본 + 4~5 블러 |
| `PHOTO_REVEAL_STEP_4` | 4 | 1~4번 | 1~4번 원본 + 5 블러 |
| `COMPLETED` | 5 | 전부 | 1~5번 원본 |

- 단계 수가 사진 장수 상한(5)과 1:1로 정합한다. enum이 `revealedOriginalCount()`를 노출하므로 매핑 로직이 enum에 캡슐화된다.
- **사진이 5장 미만인 피드**: 실제 공개 장수는 `min(revealedOriginalCount, 보유 사진 수)`로 캡한다. 예) 사진 3장 + `COMPLETED`(5) → 3장 전부 공개.
- 피드 조회(매칭 전)는 progressStatus와 무관하게 항상 블러본만 노출한다(revealedOriginalCount=0과 동일).

---

## 4. 도메인 의존성 처리 (✅ 의존 방향 / 🧩 구현 세부)

- `progressStatus` → **chat 도메인** 소유
- `original_key` / 블러본 키 → **feed 도메인**(`FeedImage`) 소유
- 프로젝트 규칙상 의존 방향은 **`chat → member` 단방향**이며, chat이 feed의 내부(key 구조)를 직접 알면 안 된다.

**해결책**: 원본 Presigned GET **발급 책임을 feed 도메인 서비스**에 둔다.

- chat은 `ChatProgressStatus.revealedOriginalCount()`로 **공개 장수 N**을 얻은 뒤, feed 서비스에 *"이 회원 원본 중 `displayOrder` 1..N의 접근 URL을 달라"* 고만 요청한다.
- feed는 해당 사진들의 originals에 대해 Presigned GET을 발급해 돌려준다(보유 사진 수로 N을 캡). chat은 key 구조·버킷·블러본 존재를 모른 채 "장수"만 다룬다.
- progressStatus → N 변환 규칙(§3 표)은 **chat 소유**이며 enum에 캡슐화돼 있다. feed는 N만 받는다.

---

## 5. S3 버킷 설정 결정 (✅)

| 설정 | originals | variants |
|---|---|---|
| 리전 | 서울 ap-northeast-2 | 서울 ap-northeast-2 |
| 퍼블릭 액세스 차단(BPA) | **전부 ON (완전 비공개)** | **해제** (단, GET만) |
| ListBucket(목록) | 비공개 | **비공개 (절대 공개 X)** |
| 객체 소유권 | ACL 비활성화 | ACL 비활성화 |
| 암호화 | SSE-S3 | SSE-S3 |
| 버전 관리 | 비활성화 | 비활성화 |
| CORS | **필요** (Presigned PUT: AllowedOrigins=프론트 도메인, Methods=[PUT], Headers) | 필요 시 GET용 |

> ⚠️ 직전 콘솔 작업에서 BPA를 해제했던 버킷이 originals 용도라면 **BPA를 다시 전부 ON으로 되돌릴 것.** 원본은 절대 공개되면 안 된다.

> ℹ️ **위 "ListBucket 비공개"는 퍼블릭/익명 접근(버킷 정책) 기준이다.** 백엔드(EC2) IAM 자격증명에는 variants 버킷에 대한 `s3:ListBucket`을 **부여한다.** 스케줄러(#51)의 `HeadObject`가 객체 부재 시 403이 아니라 **404를 받기 위함**이며(AWS는 호출 ID에 `s3:ListBucket`이 없으면 부재 객체를 403으로 가린다 → 부재 객체가 영원히 `PROCESSING`에 고착), prefix 열거를 퍼블릭에 여는 것과 무관하다. → §8-5

### 비공개인데 어떻게 제공하나 (핵심)

- **업로드**: Presigned **PUT** — 버킷이 비공개여도 서명으로 PUT 가능.
- **원본 조회**: Presigned **GET** — 백엔드가 IAM 자격증명으로 서명한 단기 URL. 버킷 비공개를 유지한 채 일정 시간만 접근 허용.
- Presigned URL 발급 자체는 **AWS 호출 없는 서명 계산**이라 추가 비용 0. 실제 비용은 그 URL로 GET이 일어날 때의 평범한 S3 요청·전송뿐.

---

## 6. CloudFront를 쓰지 않는 이유 (✅)

추가 고정 비용을 최소화하는 방침에 따라 CloudFront는 도입하지 않는다.

| 항목 | Presigned GET | CloudFront |
|---|---|---|
| 추가 고정 비용 | **없음** | 배포 비용 + 전송 |
| 비공개 버킷 호환 | ✅ | ✅ (OAC 필요) |
| 구현 복잡도 | 낮음 | 배포·OAC·캐시 설정 |

CloudFront는 **반복 조회 캐싱 이득이 클 때** 의미가 있는데, 원본 공개는 채팅 단계 전환당 한 번 일어나는 드문 이벤트라 캐싱 이득이 거의 없다. 피드 블러본은 **공개 버킷 GET이라 브라우저 캐시가 그대로 동작**한다. → 이 용도엔 Presigned GET이 더 싸고 단순하다.

---

## 7. Lambda 비용 (✅: 사실상 무료)

- Lambda 무료 한도: **매달 100만 요청 + 400,000 GB-초** (영구, 만료 없음).
- 호출 횟수 = **사진 업로드 건수** (조회 트래픽과 무관, 조회는 Lambda 미경유). 사진당 블러본 1장이라 호출이 더 적다.
- 계산: ARM 1GB, 호출당 2.5초, 월 업로드 5,000건 → 12,500 GB-초 (무료 한도의 3%). **무료**.
- 무료 한도를 0으로 가정해도 ≈ **월 $0.17** 수준.

### 비용 함정 회피

1. **Lambda를 VPC에 넣지 말 것** → NAT Gateway(GB당 $0.045) 회피. S3만 IAM으로 접근.
2. **API Gateway 불필요** → S3 이벤트 트리거라 HTTP 게이트웨이 없음.
3. **배포는 zip + Layer로** → 컨테이너 이미지 시 ECR 저장 비용(GB당 월 $0.10) 발생.
4. variants 추가 저장 용량은 장당 수십~수백 KB라 사실상 무료.

---

## 8. 블로커 확정 요약 (✅ 전부 확정)

> ✅ **확정(블로커 #1) — `FeedImage` 스키마.** `display_order` + `original_key`(비공개 원본 key, len 512) + `variant_key`(공개 블러본 key, len 512) + `blur_level`(50~100) + `processing_status`, 전부 `nullable=false`. `uk_feed_image_order(feed_id, display_order)` 유니크 유지. 블러본 공개 URL은 `baseUrl + variant_key`로 조립하고, 원본은 `original_key`로 Presigned GET 발급. → [`FEED_IMAGE_DOMAIN.md`](./FEED_IMAGE_DOMAIN.md) §2-1
>
> ✅ **확정(블로커 #2) — progressStatus ↔ 공개 장수.** `ChatProgressStatus`(chat 소유)가 `revealedOriginalCount`(0~5)를 보유: `MATCHED`=0 … `PHOTO_REVEAL_STEP_1~4`=1~4 … `COMPLETED`=5. 실제 공개 장수는 `min(count, 보유 사진 수)`로 캡. → §3
>
> ✅ **확정(블로커 #3) — 블러본 미생성 윈도.** `FeedImage.processingStatus`(`PROCESSING`→`READY`/`FAILED`)로 추적. 시간 기반 FAILED + 백엔드 스케줄러 폴링. **SQS/DLQ는 v1 미도입.** → §8-1
>
> ✅ **확정(블로커 #4) — blur_level 전달.** 원본 S3 객체 사용자 메타데이터(`x-amz-meta-blur-level`)로 전달. 백엔드가 `PutObjectRequest.metadata("blur-level", value)`로 서명에 pin하고 `requiredHeaders`로 내려주면 프론트가 그대로 PUT. 키 인코딩 미사용. Lambda가 metadata에서 읽음. → §2 "blur_level 전달"

### 8-1. 처리 상태 추적 — 시간 기반 FAILED + 스케줄러 폴링 (✅ 확정)

v1은 **SQS + DLQ를 도입하지 않는다.** 현재 목표는 "Lambda 비동기 처리로 블러본이 아직 없을 때 깨진 이미지가 노출되는 문제"를 막는 것이다. Lambda는 DB에 접근하지 않는 S3 in → S3 out 무상태 함수이고, 원본/블러본 key를 DB에 기록하는 주체는 백엔드이므로, **상태 추적은 백엔드 스케줄러의 S3 `HeadObject` 폴링**으로 충분하다.

**상태 enum (단순화 — `check_count` 미도입)**

```java
public enum FeedImageProcessingStatus {
    PROCESSING,
    READY,
    FAILED
}
// PROCESSING → READY   (HeadObject 성공)
// PROCESSING → FAILED  (created_at + 5분 초과까지 미확인)
// FAILED → 재업로드      (사용자가 새 이미지 업로드)
```

> **왜 횟수(`check_count`)가 아니라 시간(`created_at + 5분`)인가**
> 횟수 기반은 스케줄러가 잠깐 죽으면 카운트가 멈춰 판정이 지연된다. 시간 기반은 스케줄러가 죽었다 다시 떠도 `created_at`만 보고 즉시 `FAILED` 판정이 가능해 더 안전하다. 별도 카운터 컬럼도 불필요하다.

**스케줄러 사양 (✅ 확정)**

| 항목 | 값 |
|---|---|
| 실행 주기 | **1분마다** (`@Scheduled`) |
| 확인 대상 | `processing_status = PROCESSING` |
| 최초 확인 지연 | `created_at <= now - 30초` (생성 직후 Lambda 미완료 구간 폴링 낭비 방지) |
| 한 번에 확인할 개수 | **100개** |
| 정렬 | `created_at ASC` (오래된 것부터) |
| 존재 확인 | `variant_key`에 대해 variants 버킷 `HeadObject` |
| `READY` 조건 | `HeadObject` 200 성공 |
| `FAILED` 조건 | `HeadObject` 실패 && `created_at <= now - 5분` |
| 5분 이내 미확인 | `PROCESSING` 유지, 다음 주기 재확인 |
| 중복 실행 방지 | 단일 서버 기준 단순 `@Scheduled` (다중 인스턴스 전환 시 락 도입 검토) |

**피드 노출 / 실패 UX (✅ 확정)**

- **공개 피드**: 현재 DB 목록의 **모든 이미지가 `READY`일 때만** 피드를 노출한다. 하나라도 `PROCESSING`/`FAILED`면 비노출(전부-또는-비노출). 깨진 블러본이 새지 않게 하는 핵심 게이트.
- **본인 관리 조회**: `PROCESSING`/`FAILED`를 함께 내려 진행/실패를 보여준다. `FAILED`가 하나라도 있으면 **해당 사진 재업로드를 안내**한다(자동 재시도·Lambda 재트리거는 v1 미도입).

> **v2 전환 트리거**: 운영 중 실패율이 높거나 UX 문제(블러 지연 체감 등)가 발생하면 SQS + DLQ + 자동 재처리를 v2로 도입한다.

### 8-2. full-replace ↔ 비동기 Lambda 충돌 처리 (✅ 확정)

이미지 저장은 기존 v1의 **full-replace**(요청 목록을 피드 사진 전체 집합으로 보고 대체 저장)를 유지한다. 단 full-replace는 **DB의 현재 이미지 목록 기준으로만** 적용하며, S3 객체를 저장 요청 시점에 즉시 삭제하지 않는다.

Lambda는 S3 `ObjectCreated` 이벤트로 비동기 실행되므로, 사용자가 업로드 직후 사진 목록을 다시 저장하면 이전 원본에 대한 Lambda가 뒤늦게 완료될 수 있다. 이 경우 이전 variant 객체가 생성되더라도 해당 `FeedImage`가 DB 현재 목록에 없으면 피드 응답에 사용하지 않는다. **현재 피드 이미지의 기준은 S3 객체 존재 여부가 아니라 DB의 `FeedImage` 목록이다.**

- full-replace는 유지한다.
- 새 업로드는 항상 **새 `original_key` / `variant_key`** 를 사용한다. 기존 key에 원본·블러본을 덮어쓰지 않는다.
- full-replace로 제거된 이미지의 S3 객체는 **즉시 삭제하지 않는다**. 제거된 originals/variants 객체는 고아 객체로 보고 S3 Lifecycle 또는 cleanup batch로 정리한다(§8-4).
- Lambda가 늦게 완료해 이전 variant를 생성하더라도 DB에서 참조하지 않으므로 무시한다.
- 공개 피드는 현재 DB 목록의 모든 이미지가 `READY`일 때만 노출한다(§8-1과 동일 게이트).

### 8-3. blur_level 변경 시 재생성 정책 (✅ 확정)

`blur_level`은 Lambda가 블러본을 생성할 때 쓰는 처리 파라미터로, 원본 업로드 시 `x-amz-meta-blur-level` 메타데이터로 S3 객체에 고정되어 Lambda에 전달된다.

**v1은 기존 사진의 `blur_level`만 단독 변경하는 기능을 지원하지 않는다.** 변경하려면 사용자가 해당 사진을 새로 업로드해야 한다.

> **이유**: 기존 원본의 `blur_level`만 DB에서 바꿔도 S3 `ObjectCreated` 이벤트가 재발생하지 않아 Lambda가 자동 재실행되지 않는다. 재생성을 지원하려면 백엔드의 Lambda 직접 호출, SQS 재처리, copy-object 기반 이벤트 재발생 등 추가 구조가 필요하다. v1에서는 구현 난이도 대비 이득이 작아 도입하지 않는다.

- `blur_level` 단독 수정 API는 제공하지 않는다.
- `blur_level` 변경은 새 이미지 업로드로 처리하며, 새 `original_key` / `variant_key`를 발급한다(기존 객체 비덮어쓰기).
- 기존 이미지는 full-replace 과정에서 DB 목록에서 제거되고, 제거된 S3 객체는 cleanup 대상으로 둔다(§8-4).

### 8-4. S3 Lifecycle / 고아 객체 정리 (✅ v1 범위 확정)

| 버킷 | 성격 | v1 정책 |
|---|---|---|
| originals | 비공개 원본. DB 미참조 고아 발생 가능 | 즉시 삭제 안 함. 후속 Lifecycle(예: 30일) 또는 cleanup batch |
| variants | 공개 블러본. DB 미참조 old variant 발생 가능 | 즉시 삭제 안 함. 후속 Lifecycle(예: 30일) 또는 cleanup batch |

- **v1**: S3 객체를 즉시 삭제하지 않는다. Lifecycle은 **미완료 멀티파트 업로드 정리** 정도만 먼저 설정한다. DB에서 참조 해제된 객체는 후속 cleanup batch 또는 별도 prefix 정책으로 7~30일 후 삭제한다.
- **v1.1**: replaced/deleted key를 수집해 7~30일 후 batch delete.

### 8-5. 운영 / 인프라 확정값

| 항목 | 확정값 |
|---|---|
| Lambda 런타임 | **Python 3.x + Pillow** |
| 배포 방식 | **zip + Lambda Layer** (컨테이너 이미지 미사용) |
| VPC 연결 | **하지 않음** (NAT Gateway 회피, S3만 IAM 접근) |
| 트리거 | S3 `ObjectCreated` |
| Lambda 역할 | originals 객체 읽기 → variants 객체 쓰기 |
| 블러 처리 | ① 작게 다운스케일 → ② Gaussian Blur(radius = blur_level 반영) → ③ 목표 크기로 업스케일 → ④ jpg 저장 |
| `blur_level` 범위 | **50 ~ 100 정수**, 기본값 **80** (50=최소 허용 블러, 100=최강). 백엔드 DTO에서 50~100 강제. 정확한 수치는 샘플 이미지 테스트 후 보정 |
| Presigned GET 만료 | **5분**. 대상=originals 원본. 채팅방 참여자 + progressStatus 공개 장수 충족 시 발급, 재조회 시 재발급 |
| AWS 자격증명 | 운영 서버가 **access key / secret key를 `.env`로 보유**(구현 단순화 목적). IAM 역할 미사용 |
| 운영 서버 IAM 권한 | originals: `s3:PutObject` + `s3:GetObject` (`/*`) · variants: 객체 `s3:GetObject` (`/*`) + **버킷 `s3:ListBucket`** (버킷 ARN). `ListBucket`은 `HeadObject`가 부재 객체를 404로 받기 위함 — **미부여 시 403으로 가려져 `PROCESSING` 고착**(§8-1·§5). 이 ListBucket은 백엔드 IAM에만 부여하며 퍼블릭/버킷 정책의 "ListBucket 비공개"와 별개다 |

---

## 9. 현재 코드와의 차이 (구현 영향)

본 결정은 현재 구현된 v1(`FEED_IMAGE_DOMAIN.md` 기준)을 일부 대체한다. 위 TODO(특히 `FeedImage` 스키마·블러본 미생성 윈도)가 확정되면 아래를 리팩터링한다.

| 영역 | 현재 v1 | 목표(본 문서) |
|---|---|---|
| 버킷 | 단일 버킷 1개 | originals(비공개) + variants(공개) 2개 |
| 업로드 | N장 Presigned PUT, 프론트가 `imageUrl`·`blurLevel` 제출 | 원본 1장만 originals에 PUT, 프론트는 블러 미관여 |
| 블러 | 프론트 책임(`blur_level`을 표시 강도로 저장) | 서버(Lambda)가 `blur_level` 강도로 블러본 1장 생성 |
| `blur_level` 의미 | 프론트가 화면에 적용한 표시 강도 | **Lambda에 전달하는 블러 강도 지정값**(객체 메타데이터로 전달) |
| 저장 컬럼 | `image_url`(공개 URL), `blur_level` | `original_key`(비공개 원본 key) + `variant_key`(공개 블러본 key) + `blur_level` |
| 피드 노출 | 저장된 공개 URL | 블러본 공개 URL |
| 원본 조회 | (없음) | 채팅 단계 충족 시 `displayOrder` 순서대로 원본 1장씩 Presigned GET |
| 인프라 | `global.storage`(단일 버킷 PUT/공개 URL) | 2버킷 + Presigned GET + Lambda(별도 배포) |

> 코드 위치: 엔티티 `feed/domain/FeedImage.java`, 서비스 `feed/service/FeedImageService.java`, 인프라 `global/storage/`. 리팩터링 착수 전 이 표의 TODO를 먼저 확정한다.

---

## 부록: 보안 원칙 요약

- **신뢰는 서버에.** "원본을 줄지/몇 장 줄지" 판단은 항상 백엔드가 한다. 클라이언트 주장을 믿지 않는다.
- **블러는 복원 불가하게.** 다운스케일로 고주파 정보를 물리적으로 제거 → 디컨볼루션 복원 차단.
- **원본은 취소 가능한 단기 URL로만.** 만료·신고·탈퇴 시 발급을 멈추면 차단됨. 공개 URL은 한 번 새면 영구 노출이라 금지.
- **공개되는 건 블러본뿐.** 점진적으로 풀리는 원본은 항상 비공개 버킷 + 단계 검증된 Presigned GET으로만 나간다.
- **variants 목록 권한 금지.** GET만 공개, prefix 열거 차단.
