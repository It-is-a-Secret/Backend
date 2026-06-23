# 피드 이미지 도메인 설계 문서

BlurSome의 피드 이미지(`FeedImage`) 도메인을 정의합니다. 회원의 피드에 노출되는 사진을 순서·URL·블러 강도와 함께 관리하며, 사진 파일 자체는 백엔드를 거치지 않고 **S3 Presigned URL**을 통해 프론트엔드가 직접 업로드합니다.

> 상태 표기
> - ✅ **확정**: 의사결정으로 본 문서/코드에 반영됨
> - 🧩 **설계(제안)**: 방향은 정했으나 일부 후속 보강 예정
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따릅니다. 상위 피드 도메인은 [`FEED_DOMAIN.md`](./FEED_DOMAIN.md)를 참조합니다.

> ⚠️ **블러 모델 변경 — 일부 전제 대체됨.** 사진 1장당 **원본(비공개) + 블러본(공개) 2장**만 존재한다. 피드에는 블러본이 나가고, 채팅에서 공개 단계가 오를수록 **`display_order` 순서대로 원본을 1장씩** 공개한다(흐림 강도를 푸는 사다리가 아님). 블러 생성 책임은 **프론트(CSS) → 서버(Lambda)** 로 이동하고, 단일 공개 버킷이 **originals(비공개)/variants(공개) 2버킷**으로 분리된다. 자세한 결정은 [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md)를 참조하세요. 본 문서는 **현재 구현된 v1**을 기술하며, 해당 결정서의 스키마 TODO가 확정되면 리팩터링됩니다. 아래에서 v1과 목표가 다른 항목은 ⛔로 표기합니다.

> 본 문서는 v1 구현(엔티티/서비스/컨트롤러/`global.storage` 인프라)이 반영된 상태입니다. 블러·버킷 구조는 위 파이프라인 결정서가 최신입니다.

---

## 1. 개요

### 목표

- 피드에 노출할 사진을 **순서(`display_order`)·원본 key(`original_key`)·블러본 key(`variant_key`)·블러 강도(`blur_level`)** 와 함께 보관한다. (공개 URL이 아니라 S3 객체 key를 저장한다.)
- 한 피드(`Feed`)는 여러 장의 사진을 가질 수 있으므로 **`Feed` 1 : N `FeedImage`** 로 모델링한다.
- 사진 바이너리는 백엔드가 중계하지 않는다. 백엔드는 S3에 업로드할 수 있는 **임시 허가증(Presigned URL)** 만 발급하고, 프론트엔드가 S3로 파일을 직행시킨다.
- **이미지 크기·비율 가공은 프론트엔드가 담당**한다(리사이징/비율 조정). 백엔드는 이미지 리사이징·크롭을 수행하지 않는다.
- ⛔ **블러 생성 책임은 서버(Lambda)로 이동했다.** v1은 프론트가 `blur_level`(50~100)을 제출해 저장하지만, 목표 구조에서는 **서버사이드 Lambda가 사진당 블러본 1장을 생성**하고 프론트는 블러에 관여하지 않는다. CSS 블러는 우회 가능하므로 금지한다. → [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md)
- ⛔ **단계 공개는 흐림 강도가 아니라 원본 장수로 표현된다.** 사진당 블러본은 1장으로 고정이고, 채팅 `progressStatus`가 오를수록 `display_order` 순서대로 원본을 1장씩 공개한다. v1은 단계 공개 개념이 없다.
- ⛔ **버킷은 originals(비공개)/variants(공개) 2개로 분리한다.** v1은 단일 버킷이며, 목표 구조에서는 원본을 비공개 originals에, 블러본을 공개 variants에 둔다.
- 모든 컬럼은 `nullable=false` 로 두어, `FeedImage` 행이 존재하면 항상 완전한 정보를 갖도록 한다.

### 핵심 결정

| 항목 | 결정 | 상태 | 비고 |
|---|---|---|---|
| `Feed` 관계 | **`@ManyToOne(LAZY)`**, `feed_id` FK, `nullable=false` | ✅ | 피드 1 : N 이미지 |
| 컬럼 | `display_order`·`original_key`·`variant_key`·`blur_level` 전부 `nullable=false` | ✅ | 불완전한 행 차단 |
| 업로드 방식 | **S3 Presigned URL (Direct Upload)** | ✅ | 백엔드는 파일 바이너리에 비관여 |
| 이미지 가공 책임 | **프론트엔드가 크기·비율 조정**(리사이징만) | ✅ | 백엔드는 리사이징 미수행 |
| 블러 생성 책임 | ⛔ **서버사이드(Lambda)**, 사진당 블러본 1장 | ✅ | v1은 프론트 `blur_level` 제출. → 파이프라인 결정서 |
| 단계 공개 방식 | ⛔ `progressStatus`에 따라 **원본을 `display_order` 순서대로 1장씩** | ✅ | 흐림 강도 사다리 아님. v1은 단계 공개 없음 |
| 버킷 구조 | ⛔ **originals(비공개)/variants(공개) 2버킷** | ✅ | v1은 단일 버킷. → 파이프라인 결정서 |
| 저장 컬럼 | **`original_key`(비공개 원본)·`variant_key`(공개 블러본) 둘 다 S3 key 저장** | ✅ | 공개 URL이 아니라 key를 저장. 블러본 공개 URL은 `baseUrl + variant_key`로 조립, 원본은 `original_key`로 Presigned GET |
| key 컬럼 길이 | `original_key`·`variant_key` 각 `length=512` | ✅ | S3 객체 key 기준 |
| `blur_level` 타입·범위 | `Integer`, **50 ~ 100** | ✅ | ⛔ 의미 변경: v1은 프론트 표시 강도, 목표는 **Lambda 블러 강도 지정값**(객체 메타데이터로 전달) → 파이프라인 §1·§2 |
| 블러본 생성 상태 | **`processing_status`(`PROCESSING`→`READY`/`FAILED`)**, 백엔드 스케줄러가 `HeadObject`로 전이 | ✅ | 시간 기반 FAILED(`created_at`+5분). 공개 피드는 전부 `READY`일 때만 노출 → §2-5 |
| `display_order` 범위 | **1 ~ 5 (피드당 최대 5장)** | ✅ | 1-base, 피드당 사진 5장 상한 |
| `(feed_id, display_order)` 유니크 | `uk_feed_image_order`로 한 피드 내 순서 중복 방지 | ✅ | DB 레벨 무결성 |

---

## 2. 도메인 모델

### 2-1. FeedImage (`feed_image`)

피드에 노출되는 사진 1장. `Feed`와 N:1로 대응한다.

| 필드 | 타입 / 제약 | 설명 |
|---|---|---|
| `id` | PK, IDENTITY | — |
| `feed` | `Feed` `@ManyToOne(LAZY)`, `feed_id`, `nullable=false` | 소속 피드 (`Feed.id`를 FK로 보유) |
| `displayOrder` | `Integer`, `nullable=false`, col `display_order` | 피드 내 사진 노출 순서. **1 ~ 5 (1-base), 피드당 최대 5장** |
| `originalKey` | `String`, `nullable=false`, len 512, col `original_key` | **비공개** originals 버킷의 원본 객체 key. 채팅 단계 충족 시 이 key로 Presigned GET 발급 |
| `variantKey` | `String`, `nullable=false`, len 512, col `variant_key` | **공개** variants 버킷의 블러본 객체 key. 피드 노출 URL은 `baseUrl + variant_key`로 조립 |
| `blurLevel` | `Integer`, `nullable=false`, col `blur_level` | Lambda 블러 강도 지정값 (**50 ~ 100**, 기본 80) |
| `processingStatus` | `FeedImageProcessingStatus` enum, `nullable=false`, col `processing_status` | 블러본 생성 상태: `PROCESSING` → `READY` / `FAILED` |
| `createdAt`/`updatedAt` | `BaseEntity` | — |

> **`nullable=false` 이유**: `FeedImage`는 프론트가 원본 S3 업로드를 마치고 메타데이터 저장을 요청한 시점에만 생성된다. 이때 `original_key`는 업로드 대상 key이고, `variant_key`는 원본 uuid 기반 **결정론적 규칙으로 계산**되며, `blur_level`은 요청에 포함되고, `processing_status`는 `PROCESSING`으로 시작하므로 모든 컬럼이 항상 값을 갖는다.
>
> ⚠️ **`variant_key`가 non-null이라고 해서 블러본 객체가 S3에 존재함을 보장하지는 않는다.** key 문자열은 결정론적이라 즉시 알 수 있으나, 실제 블러본은 Lambda가 비동기로 생성한다. 이 미생성 윈도는 `processing_status` 컬럼으로 추적한다(§2-5).

### 2-2. `blur_level` 타입·범위 (✅ 확정)

> **`blur_level`은 Lambda 블러 강도 지정값이다(컬럼 확정).** v1에서는 프론트가 화면에 적용하는 표시 강도였으나, 확정 구조에서는 **Lambda가 블러본을 만들 때 적용할 블러 강도 지정값**이다. 프론트가 사진별로 강도를 고르면, 그 값을 원본 S3 객체 메타데이터(`x-amz-meta-blur-level`)로 Lambda에 전달해 픽셀에 굽는다(B 방식 유지). 단계 공개는 이 강도와 무관하게 원본 장수로 표현된다. → 파이프라인 결정서 §1·§2

- 타입: **`Integer`**. 프론트가 사진별로 지정하는 블러 강도값.
- 유효 범위: **`50 ~ 100`**. 최소 50으로 일정 수준 이상의 블러를 보장하고, 100을 상한으로 둔다. (목표 구조에서 Lambda가 이 값으로 가우시안 강도를 정한다.)
- 검증: 요청 DTO에서 `@Min(50)` / `@Max(100)`으로 강제한다(필요 시 DB `CHECK` 보강 검토). 클라이언트 지정값이라도 하한이 강제되어 약하게 굽는 것을 막는다.
- 전달: 목표 구조에서는 Presigned PUT 발급 시 백엔드가 객체 메타데이터로 pin하고, Lambda가 읽어 사용한다. 백엔드는 메타데이터 저장 단계에서 `FeedImage`에도 기록한다.

### 2-3. `display_order` 범위·장수 제한 (✅ 확정)

- 범위: **`1 ~ 5` (1-base)**. 피드당 **최대 5장**의 사진을 저장한다.
- 검증: 메타데이터 저장 요청 DTO에서 `@Min(1)` / `@Max(5)`로 각 값을 검증하고, 요청 이미지 목록 크기를 `@Size(max = 5)`로 제한한다. 서비스 레벨에서 기존 이미지 수와 합산해 5장을 넘지 않도록 보장한다.
- 무결성: `(feed_id, display_order)` 유니크 제약(`uk_feed_image_order`)으로 한 피드 내 순서 중복을 DB 레벨에서 차단한다.

### 2-4. 제약 조건

| 제약 | 정의 | 목적 |
|---|---|---|
| `uk_feed_image_order` | `(feed_id, display_order)` 유니크 | 한 피드 내 순서 중복 방지 |
| FK `feed_id → feed.id` | `nullable=false` | 고아 이미지 차단 |

> **인덱스**: 피드별 이미지 조회가 잦으므로 `feed_id` 인덱스가 필요하다. `uk_feed_image_order`의 선두 컬럼이 `feed_id`이므로 별도 인덱스 없이 해당 유니크 제약이 조회 인덱스 역할을 겸한다.

### 2-5. 블러본 생성 상태 `processing_status` (✅ 확정)

블러본은 Lambda가 **비동기**로 생성하므로, 메타데이터 저장 직후에는 `variant_key`가 가리키는 객체가 아직 S3에 없을 수 있다. 이 미생성 윈도를 `FeedImageProcessingStatus`로 추적한다. Lambda는 DB를 모르므로(B 방식), **백엔드 스케줄러가 S3 `HeadObject`로 존재를 폴링**해 상태를 전이시킨다. **v1은 SQS/DLQ를 도입하지 않고 시간 기반 FAILED + 스케줄러 폴링으로 간다.**

```java
public enum FeedImageProcessingStatus {
    PROCESSING,   // 메타데이터 저장 직후 기본값. 블러본 미확인
    READY,        // HeadObject 200 — 블러본 존재 확인, 공개 노출 가능
    FAILED        // created_at + 5분 초과까지 블러본 미확인
}
```

**라이프사이클 (시간 기반 판정)**

```
③ 메타데이터 저장        processing_status = PROCESSING
        │
        ▼  (@Scheduled 1분마다, PROCESSING 행 점검)
   created_at <= now - 30초 인 행만, created_at ASC, 최대 100개
   → variant_key 에 HeadObject
        ├── 200 OK                         → READY
        └── 실패 && created_at <= now - 5분 → FAILED
        (실패 && 5분 이내 → PROCESSING 유지, 다음 주기 재확인)
```

**스케줄러 사양**: 주기 1분 · 대상 `processing_status = PROCESSING` · 최초 확인 지연 `created_at <= now-30초` · 1회 최대 100개 · 정렬 `created_at ASC` · `READY` 조건 `HeadObject` 성공 · `FAILED` 조건 `HeadObject` 실패 && `created_at <= now-5분` · 중복 실행 방지는 단일 서버 기준 단순 `@Scheduled`.

> **시간 기반(`created_at`)을 쓰는 이유**: 횟수(`check_count`) 기반은 스케줄러가 죽으면 카운트가 멈춰 판정이 지연되지만, 시간 기반은 스케줄러가 재시작돼도 `created_at`만 보고 즉시 `FAILED`를 판정할 수 있어 더 안전하다. 별도 카운터 컬럼을 두지 않는다.

- **공개 피드 조회**(다른 회원이 보는 피드): 현재 DB 목록의 **모든 이미지가 `READY`일 때만** 피드를 노출한다(전부-또는-비노출). 하나라도 `PROCESSING`/`FAILED`면 비노출 — 깨진 블러본 노출 차단의 핵심 게이트.
- **본인 피드 관리 조회**(`/api/feeds/me/...`): `PROCESSING`·`FAILED`도 함께 내려 업로드 진행/실패를 보여준다. `FAILED`가 하나라도 있으면 **해당 사진 재업로드를 안내**한다(v1은 자동 재시도·Lambda 재트리거 없음).
- 스케줄러는 `HeadObject`만 호출하므로 객체 바이트를 받지 않아 비용·전송이 거의 없다. Lambda 통지 없이 B 방식을 유지한다.
- 운영 중 실패율·UX 문제가 커지면 SQS + DLQ + 자동 재처리를 v2로 도입한다. 상세 사양은 [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §8-1.

---

## 3. `Feed`와의 관계

```
Feed (1) ──────────── (N) FeedImage
  └── feed_image.feed_id → feed.id
```

- `Feed`는 공개 프로필(닉네임·성별·생년·학과·MBTI)을, `FeedImage`는 피드에 노출되는 사진 목록을 갖는다.
- 양방향 매핑 여부는 선택이다.
  - **권장(단방향)**: `FeedImage`만 `@ManyToOne` 보유. `Feed`에는 컬렉션을 두지 않고, 조회는 `FeedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId)`로 처리한다. 엔티티 그래프를 단순하게 유지하고 N+1·cascade 부작용을 피한다.
  - 양방향이 필요해지면 `Feed`에 `@OneToMany(mappedBy="feed")`를 추가한다.

---

## 4. 사진 적재 플로우 (S3 Presigned URL / Direct Upload)

> ⛔ **아래는 v1 플로우다.** 목표 구조에서는 프론트가 **원본 1장만 비공개 originals 버킷에 PUT**하고(원하는 `blur_level`을 함께 지정 → 객체 메타데이터로 pin), 블러본은 Lambda가 그 강도로 생성한다. 저장은 `image_url`(공개 URL) 대신 `original_key` + 블러본 공개 URL을 기록하고 `blur_level`은 유지한다. 단계별 원본 공개는 채팅에서 Presigned GET으로 처리한다. → [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §2

백엔드는 파일 업로드에 직접 관여하지 않는다. S3에 파일을 올릴 수 있는 **임시 허가증(Presigned URL)** 만 프론트에 발급하고, 프론트가 S3로 파일을 직행시킨다. 이미지의 크기·비율 가공도 프론트가 업로드 전에 마친다.

```
⓪ 이미지 가공 (프론트엔드)
   FE: 사용자가 선택한 사진을 정책에 맞는 크기·비율로 리사이징/조정

① Presigned URL 발급 요청
   FE → BE   POST /api/feeds/me/images/presigned-urls
             { files: [ { fileName, contentType }, ... ] }
                    │
                    ▼
   BE: 각 파일에 대해 S3 객체 key 생성(feeds/{memberId}/{uuid}.{ext})
       S3Presigner로 PUT용 Presigned URL 생성(짧은 만료, 기본 5분)
       응답: { uploads: [ { uploadUrl(Presigned PUT), objectKey, imageUrl(업로드 후 접근 URL) }, ... ] }

② S3 직접 업로드 (백엔드 미경유)
   FE → S3   PUT {uploadUrl}   (body=파일 바이너리, Content-Type 일치)

③ 메타데이터 저장 요청
   FE → BE   POST /api/feeds/me/images
             { images: [ { imageUrl, displayOrder, blurLevel }, ... ] }
                    │
                    ▼
   BE: 인증된 memberId로 본인 피드 조회(회원:피드 1:1이라 소유권 검증 불필요)
       displayOrder(1~5)·blurLevel(50~100)·장수(최대 5장)·순서 중복 검증
       기존 이미지 전체 삭제 후 요청 목록으로 대체 저장(full replace)
```

> **엔드포인트 컨벤션**: 회원은 자신의 피드(회원:피드 1:1)에만 사진을 등록하므로, 경로에 `feedId`를 받지 않고 `/api/feeds/me/images`로 인증 주체에서 피드를 도출한다(`/api/members/me`와 동일 컨벤션). IDOR 여지를 없앤다.
>
> **저장 의미(full replace)**: 메타데이터 저장 요청의 `images` 목록을 해당 피드 사진의 **전체 집합**으로 간주한다. 저장 시 기존 이미지를 모두 삭제하고 요청 목록으로 대체하므로, 사진 추가·삭제·재정렬이 단일 요청으로 처리된다.
>
> ⚠️ **full-replace는 DB 행만 대체하고 S3 객체는 즉시 삭제하지 않는다.** 새 업로드는 항상 새 `original_key`/`variant_key`를 쓰고 기존 객체를 덮어쓰지 않는다(비동기 Lambda가 늦게 완료해도 DB 미참조 객체는 피드에 쓰지 않음). 제거된 객체는 고아로 두고 후속 cleanup batch/Lifecycle로 정리한다. `blur_level` 변경도 단독 수정 API 없이 새 업로드로 처리한다. → [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §8-2·§8-3

### 4-1. 책임 분리

| 단계 | 주체 | 역할 |
|---|---|---|
| 이미지 가공 | 프론트 | 크기·비율 리사이징/조정. 백엔드는 가공 미수행 |
| Presigned URL 발급 | 백엔드 | S3 객체 key 결정, PUT용 서명 URL 생성, 만료시간 설정 |
| 파일 업로드 | 프론트 ↔ S3 | 바이너리 전송. 백엔드 미경유 |
| 메타데이터 저장 | 백엔드 | `FeedImage`(`image_url`/`display_order`/`blur_level`) 영속화(full replace), 범위·장수·순서중복 검증 |

### 4-2. 설계 시 유의점 (🧩 / ⏳)

- **객체 key 규칙**: `feeds/{memberId}/{uuid}.{ext}` 형태를 권장한다. 회원·피드 단위로 prefix를 두면 수명주기 정책·정리가 쉽다. (⏳ 최종 규칙 결정)
- **`image_url` 저장 값**: 만료되는 Presigned GET URL이 아니라 **영구 접근 URL**(공개 버킷의 객체 URL 또는 CloudFront 도메인)을 저장한다. 비공개 버킷이면 조회 시점에 GET Presigned URL을 발급하는 방식을 별도 검토한다. (⏳)
- **업로드 검증**: ②번 직접 업로드는 백엔드가 결과를 알 수 없다. ③번 저장 시 `imageUrl`이 발급된 objectKey와 일치하는지 검증하거나, S3 `HeadObject`로 실제 존재를 확인하는 보강을 검토한다. (⏳)
- **고아 객체 정리**: ②까지 성공하고 ③이 누락되면 S3에만 파일이 남는다. S3 Lifecycle 또는 배치 정리를 검토한다. (⏳)
- **업로드 제약**: 허용 `contentType`(image/jpeg, image/png 등)·최대 파일 크기·피드당 최대 장수를 Presigned 발급 시점에 강제한다. (⏳)

---

## 5. 패키지 구조

```
com.blursome.feed
├── domain/
│   ├── Feed.java                     ✅ 생성됨
│   └── FeedImage.java                ✅ 생성됨
├── repository/
│   ├── FeedRepository.java           ✅ 생성됨
│   └── FeedImageRepository.java      ✅ 생성됨 (findByFeedIdOrderByDisplayOrderAsc, deleteByFeedId)
├── service/
│   ├── FeedService.java              ✅ 생성됨
│   └── FeedImageService.java         ✅ 생성됨 (Presigned 발급 + 메타데이터 저장/조회)
├── controller/
│   └── FeedImageController.java      ✅ 생성됨
├── dto/
│   ├── request/                      ✅ PresignedUrlRequest, FeedImageSaveRequest
│   └── response/                     ✅ PresignedUrlResponse, FeedImageResponse
└── exception/
    ├── FeedErrorCode.java            ✅ 생성됨
    └── FeedImageErrorCode.java       ✅ 생성됨

com.blursome.global.storage           ✅ S3 인프라 (횡단 관심사)
├── S3Properties.java                 ✅ app.s3.* 프로퍼티 (bucket/region/expiration/baseUrl)
├── S3Config.java                     ✅ S3Presigner 빈
├── S3StorageService.java             ✅ Presigned PUT URL 발급 + 객체 key/public URL 생성
└── PresignedUpload.java              ✅ 발급 결과 값 객체
```

---

## 6. 에러 코드 (예정)

`com.blursome.feed.exception.FeedImageErrorCode`

| 코드 | 상황 | HTTP |
|---|---|---|
| `FEED_IMAGE_400_INVALID_COUNT` | 사진 장수가 허용 범위(1~5)를 벗어남 | 400 |
| `FEED_IMAGE_400_DUPLICATE_ORDER` | 한 요청 내 `displayOrder`가 중복됨 | 400 |
| `FEED_IMAGE_404_NOT_FOUND` | 피드 이미지를 찾을 수 없음 | 404 |

> 코드 네이밍은 기존 `FeedErrorCode`(`FEED_404_NOT_FOUND`)와 동일한 `<도메인>_<HTTP>_<사유>` 규칙을 따른다.
>
> **DTO 검증 경계**: 블러 강도(50~100)·순서 범위(1~5)·장수 상한(`@Size(max=5)`)·Content-Type(`image/*`)은 요청 DTO의 Bean Validation으로 처리되어 `GlobalExceptionHandler`가 표준 검증 오류로 응답한다. `FeedImageErrorCode`는 DTO로 표현하기 어려운 도메인 규칙(순서 중복, 피드 미존재 시 `FeedErrorCode.FEED_404_NOT_FOUND`)에 사용한다. 회원:피드 1:1 + `/me` 경로로 소유권 위반(403)이 원천 차단되어 별도 코드를 두지 않는다.

---

## 7. 남은 항목 (⏳ TODO)

> ⛔ **블러 파이프라인 전환이 최우선 후속 작업이다.** 2버킷 분리·서버사이드 블러(Lambda)·`original_key` 스키마(`blur_level`은 Lambda 강도값으로 유지)·단계별 원본 Presigned GET 등은 [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §8·§9에서 관리한다. 아래는 v1 범위의 잔여 항목이다.

- **`Feed` 양방향 매핑 여부** — 현재 단방향 유지. 필요 시 `@OneToMany` 추가.
- **피드 조회 API 연동** — 다른 회원 피드 조회 응답에 이미지 목록(`displayOrder` 정렬) 포함. 공개 조회는 모든 이미지가 `READY`일 때만 노출(§2-5).

> ✅ **확정 — AWS 자격 증명 운영 구성.** 구현 단순화를 위해 운영 서버가 **access key / secret key를 `.env`로 보유**한다(IAM 역할 미사용). → [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md) §8-5

> 완료(v1): 단일 버킷 S3 인프라(`global.storage`), Presigned PUT 발급/저장/조회 API, 1~5장·50~100·순서중복 검증, full-replace 저장.

---

## 부록: 관련 코드 위치

- 블러 파이프라인 결정서: [`FEED_IMAGE_BLUR_PIPELINE.md`](./FEED_IMAGE_BLUR_PIPELINE.md)
- 상위 피드 도메인: [`FEED_DOMAIN.md`](./FEED_DOMAIN.md)
- 피드 엔티티: `src/main/java/com/blursome/blursome/feed/domain/Feed.java`
- 피드 이미지 엔티티: `src/main/java/com/blursome/blursome/feed/domain/FeedImage.java`
- 피드 이미지 서비스: `src/main/java/com/blursome/blursome/feed/service/FeedImageService.java`
- 피드 이미지 컨트롤러: `src/main/java/com/blursome/blursome/feed/controller/FeedImageController.java`
- S3 스토리지 인프라: `src/main/java/com/blursome/blursome/global/storage/`
- 아키텍처 규칙: [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)
- 코드 컨벤션: [`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)
