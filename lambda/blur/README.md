# 블러 생성 Lambda (Python + Pillow)

originals 버킷의 원본을 읽어 **블러본 1장**을 생성해 variants 버킷에 쓰는 무상태(S3 in → S3
out) Lambda. DB에 접근하지 않으며 블러 강도는 원본 객체 메타데이터(`x-amz-meta-blur-level`)
에서 읽는다.

> 설계 근거: [`docs/feed/FEED_IMAGE_BLUR_PIPELINE.md`](../../docs/feed/FEED_IMAGE_BLUR_PIPELINE.md)
> §2(데이터 흐름)·§7(Lambda 비용)·§8-5(운영/인프라 확정값)

```
lambda/blur/
├── handler.py             # 핸들러 + 블러/키 로직
├── requirements.txt       # Layer 런타임 의존성 (Pillow)
├── requirements-dev.txt   # 로컬 테스트 의존성
└── tests/test_handler.py  # 단위 테스트 (AWS 불필요)
```

## ⚠️ 키 규칙은 백엔드와 반드시 일치

블러본 출력 키는 백엔드 `S3ObjectKeyGenerator.toVariantKey`와 **동일한 규칙**이어야 한다.
어긋나면 백엔드 스케줄러의 `HeadObject` 폴링이 블러본을 찾지 못해 `FeedImage`가 영영
`READY`로 전이하지 못하고 5분 뒤 `FAILED`가 된다.

```
originals/{memberId}/{uuid}.{ext}   ->   variants/{memberId}/{uuid}.jpg   (확장자 jpg 고정)
```

## blur_level 보정(clamp) 정책

백엔드가 50~100으로 검증해 메타데이터에 pin 하지만, Lambda는 방어적으로 동작한다. (치명적
오류만 실패시키고, 그 외에는 안전한 기본 블러로 teaser 생성)

| 입력 | 처리 |
|---|---|
| 누락 | 80 (기본값) |
| 50 미만 | 50 (하한 보정) |
| 100 초과 | 100 (상한 보정) |
| 숫자 아님(파싱 불가) | 80 (기본값) |
| 원본 객체를 못 읽는 등 치명적 오류 | Lambda 실패 → 스케줄러가 5분 후 FAILED 판정 |

> 보정이 발생하면 경고 로그(CloudWatch)를 남겨 비정상 입력을 관측한다.

---

## 로컬 테스트

```bash
cd lambda/blur
python -m venv .venv && source .venv/bin/activate   # (Windows: .venv\Scripts\activate)
pip install -r requirements-dev.txt
python -m pytest tests/ -v
```

`tests/`는 AWS 없이 동작한다(S3 클라이언트는 가짜로 주입, 샘플 이미지는 Pillow로 즉석 생성).
블러 강도 보정은 `test_higher_blur_level_reduces_detail`가 50/80/100의 디테일 감소를 검증한다.
실제 사진으로 강도를 보정할 때는 `handler.py` 상단의 다음 상수를 조정한다.

- `DOWNSCALE_LONGEST_SIDE_WEAK` / `_STRONG` — 다운스케일 중간 이미지의 가장 긴 변(작을수록 강함)
- `GAUSSIAN_RADIUS_WEAK` / `_STRONG` — 가우시안 반경
- `JPEG_QUALITY`

---

## 배포 (zip + Lambda Layer, 컨테이너 이미지 미사용)

> 아래 `{...}` 플레이스홀더는 실제 값으로 채운다. 리전은 서울(`ap-northeast-2`).

### 1. Pillow Layer 빌드

Pillow는 네이티브 확장이라 **Lambda 실행 환경(Amazon Linux)과 동일한 플랫폼 wheel**로 받아야
한다. 런타임/아키텍처(예: `python3.12`, `arm64`)에 맞춰 빌드한다.

- `--platform`: arm64면 `manylinux2014_aarch64`, x86_64면 `manylinux2014_x86_64`
- `--python-version`: 함수 런타임과 일치(예: `3.12`)

```bash
cd lambda/blur
rm -rf python && mkdir python
pip install \
  --platform manylinux2014_aarch64 \
  --implementation cp \
  --python-version 3.12 \
  --only-binary=:all: \
  --target python \
  -r requirements.txt
zip -r pillow-layer.zip python
```

```bash
aws lambda publish-layer-version \
  --layer-name blursome-pillow \
  --zip-file fileb://pillow-layer.zip \
  --compatible-runtimes python3.12 \
  --compatible-architectures arm64 \
  --region ap-northeast-2
# 출력의 LayerVersionArn 을 기록
```

### 2. 핸들러 패키징

```bash
cd lambda/blur
zip handler.zip handler.py
```

### 3. 함수 생성

IAM 역할은 originals 읽기 + variants 쓰기만 갖는 최소 권한으로 미리 만든다(아래 4번).
**VPC에 연결하지 않는다**(NAT Gateway 비용 회피, S3만 IAM으로 접근).

```bash
aws lambda create-function \
  --function-name blursome-blur \
  --runtime python3.12 \
  --architectures arm64 \
  --handler handler.lambda_handler \
  --role arn:aws:iam::{ACCOUNT_ID}:role/{BLUR_LAMBDA_ROLE} \
  --layers {PILLOW_LAYER_VERSION_ARN} \
  --timeout 30 --memory-size 1024 \
  --environment "Variables={VARIANTS_BUCKET={VARIANTS_BUCKET}}" \
  --zip-file fileb://handler.zip \
  --region ap-northeast-2
```

코드만 갱신할 때:

```bash
aws lambda update-function-code \
  --function-name blursome-blur \
  --zip-file fileb://handler.zip --region ap-northeast-2
```

### 4. IAM 역할 (최소 권한)

신뢰 정책은 `lambda.amazonaws.com`. 권한 정책 예시:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::{ORIGINALS_BUCKET}/*" },
    { "Effect": "Allow", "Action": ["s3:PutObject"],
      "Resource": "arn:aws:s3:::{VARIANTS_BUCKET}/*" },
    { "Effect": "Allow",
      "Action": ["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],
      "Resource": "arn:aws:logs:ap-northeast-2:{ACCOUNT_ID}:*" }
  ]
}
```

### 5. S3 트리거 연결 (originals `ObjectCreated`)

```bash
# Lambda가 S3로부터 호출되도록 권한 부여
aws lambda add-permission \
  --function-name blursome-blur \
  --statement-id s3-originals-trigger \
  --action lambda:InvokeFunction \
  --principal s3.amazonaws.com \
  --source-arn arn:aws:s3:::{ORIGINALS_BUCKET} \
  --region ap-northeast-2

# originals 버킷에 ObjectCreated 알림 등록 (originals/ prefix로 한정)
# notification.example.json 을 복사해 {ACCOUNT_ID} 를 채운 notification.json 으로 저장 후 사용
cp notification.example.json notification.json   # 이후 {ACCOUNT_ID} 치환
aws s3api put-bucket-notification-configuration \
  --bucket {ORIGINALS_BUCKET} \
  --notification-configuration file://notification.json
```

> `notification.json`(실제 계정 ID 포함)은 `.gitignore` 대상이다. 커밋되는 건 플레이스홀더
> 템플릿 `notification.example.json` 뿐이다(`.env` / `.env.example` 와 동일 컨벤션).

> **prefix 필터**: `originals/` 외 객체로는 트리거되지 않게 해 불필요한 호출·재시도를 막는다.
> 핸들러도 방어적으로 non-original key를 건너뛰지만(이중 안전장치), 알림 단계에서 거르는 게
> 비용·소음 면에서 낫다.
>
> ⚠️ originals/variants는 **서로 다른 버킷**이어야 한다(같은 버킷이면 블러본 쓰기가 다시
> 트리거를 일으켜 무한 루프). 설계 §1·§5 참조.

### 6. 환경 변수

| 키 | 설명 |
|---|---|
| `VARIANTS_BUCKET` | 블러본을 쓸 공개 variants 버킷 이름 |

> 원본 버킷 이름은 S3 이벤트가 전달하므로 환경 변수로 받지 않는다.
