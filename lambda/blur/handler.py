"""블러 생성 Lambda (S3 in -> S3 out, 무상태).

originals 버킷에 원본이 업로드되면(S3 ``ObjectCreated``) 트리거되어, 원본 1장을
다운스케일 + 가우시안 블러로 복원 불가하게 만든 블러본 1장을 생성해 variants 버킷에
쓴다. DB에는 접근하지 않으며, 블러 강도(``blur_level``)는 원본 객체의 사용자 메타데이터
(``x-amz-meta-blur-level``)에서 읽는다.

설계: ``docs/feed/FEED_IMAGE_BLUR_PIPELINE.md`` 2장(데이터 흐름), 7장(Lambda 비용),
8-5장(운영/인프라 확정값).

키 규칙은 백엔드(`S3ObjectKeyGenerator`)와 **반드시 일치**해야 한다. 어긋나면 백엔드
스케줄러의 ``HeadObject`` 폴링이 블러본을 찾지 못해 ``FeedImage``가 영영 ``READY``로
전이하지 못하고 5분 뒤 ``FAILED``가 된다::

    originals/{memberId}/{uuid}.{ext}   ->   variants/{memberId}/{uuid}.jpg
"""

import logging
import os
from io import BytesIO
from urllib.parse import unquote_plus

import boto3
from PIL import Image, ImageFilter, ImageOps

logger = logging.getLogger()
logger.setLevel(logging.INFO)

# --- 키 규칙 (백엔드 S3ObjectKeyGenerator와 일치) ---------------------------------
ORIGINALS_PREFIX = "originals/"
VARIANTS_PREFIX = "variants/"
# Lambda는 블러본을 항상 jpg로 출력하므로 블러본 확장자는 jpg로 고정한다.
VARIANT_EXTENSION = "jpg"

# --- 블러 강도 (백엔드 DTO 검증과 동일 범위) -------------------------------------
# 원본 객체 메타데이터에서 SDK로 읽을 때의 키. 실제 객체에는 x-amz-meta-blur-level로 저장된다.
BLUR_LEVEL_METADATA_KEY = "blur-level"
MIN_BLUR_LEVEL = 50
MAX_BLUR_LEVEL = 100
DEFAULT_BLUR_LEVEL = 80

# --- 블러 처리 파라미터 (샘플 이미지 테스트로 보정) -------------------------------
# blur_level 50(약)~100(강)을 t=0~1로 정규화해 아래 값들을 선형 보간한다.
# 다운스케일 중간 이미지의 가장 긴 변 픽셀 수. 작을수록 고주파 정보가 더 많이 파괴된다.
DOWNSCALE_LONGEST_SIDE_WEAK = 64   # blur_level=50
DOWNSCALE_LONGEST_SIDE_STRONG = 24  # blur_level=100
# 다운스케일된 작은 이미지에 적용하는 가우시안 반경.
GAUSSIAN_RADIUS_WEAK = 1.0   # blur_level=50
GAUSSIAN_RADIUS_STRONG = 2.5  # blur_level=100
JPEG_QUALITY = 80
# 공개 블러본(teaser)의 가장 긴 변 상한(px). 원본이 더 크면 이 크기로 줄여 저장량/트래픽/메모리를
# 억제한다(원본보다 키우지는 않는다). variants는 장당 수십~수백 KB 전제. (설계 §7)
VARIANT_MAX_LONGEST_SIDE = 1080

# boto3 클라이언트는 import 시점이 아니라 최초 사용 시 lazy 하게 만든다(테스트가 AWS 설정 없이
# 모듈을 import 할 수 있도록).
_s3_client = None


def lambda_handler(event, context):
    """S3 ``ObjectCreated`` 이벤트를 받아 각 레코드의 원본을 블러본으로 변환한다."""
    for record in event.get("Records", []):
        src_bucket = record["s3"]["bucket"]["name"]
        # S3 이벤트의 객체 key는 URL 인코딩되어 오므로 디코딩한다(공백/한글 등).
        src_key = unquote_plus(record["s3"]["object"]["key"])
        # originals/ 외 객체(잘못된 트리거 등)는 실패시키지 않고 건너뛴다. 실패시키면 Lambda 재시도가
        # 반복돼 시끄럽다. S3 알림에도 prefix=originals/ 필터를 거는 것을 권장한다(README 참조).
        if not src_key.startswith(ORIGINALS_PREFIX):
            logger.warning("originals/ prefix가 아니어서 건너뜀: %s", src_key)
            continue
        _process_object(src_bucket, src_key)


def _process_object(src_bucket, src_key):
    """원본 1장을 읽어 블러본을 만들어 variants 버킷에 쓴다."""
    response = _client().get_object(Bucket=src_bucket, Key=src_key)
    raw_blur_level = response.get("Metadata", {}).get(BLUR_LEVEL_METADATA_KEY)
    blur_level = resolve_blur_level(raw_blur_level, src_key)
    image_bytes = response["Body"].read()

    variant_bytes = make_blur(image_bytes, blur_level)
    dst_key = to_variant_key(src_key)

    _client().put_object(
        Bucket=_variants_bucket(),
        Key=dst_key,
        Body=variant_bytes,
        ContentType="image/jpeg",
    )
    logger.info(
        "블러본 생성 완료: %s -> %s (blur_level=%d)", src_key, dst_key, blur_level
    )


def resolve_blur_level(raw_value, src_key="?"):
    """원본 메타데이터의 blur_level을 50~100 범위 정수로 보정(clamp)한다.

    백엔드가 50~100으로 검증해 pin 하지만 Lambda는 방어적으로 동작한다. 누락/파싱불가 값은
    기본값으로, 범위 밖 값은 경계로 보정해 **실패시키지 않고** 안전한 기본 블러로 teaser를
    만든다(원본은 비공개 게이팅이라 teaser 품질만 영향). 보정 시 경고 로그를 남긴다.
    """
    if raw_value is None:
        logger.warning("blur_level 메타데이터 누락 -> 기본값 %d 사용 (%s)",
                        DEFAULT_BLUR_LEVEL, src_key)
        return DEFAULT_BLUR_LEVEL
    try:
        value = int(raw_value)
    except (TypeError, ValueError):
        logger.warning("blur_level 파싱 불가(%r) -> 기본값 %d 사용 (%s)",
                       raw_value, DEFAULT_BLUR_LEVEL, src_key)
        return DEFAULT_BLUR_LEVEL
    if value < MIN_BLUR_LEVEL:
        logger.warning("blur_level %d < %d -> 하한으로 보정 (%s)",
                       value, MIN_BLUR_LEVEL, src_key)
        return MIN_BLUR_LEVEL
    if value > MAX_BLUR_LEVEL:
        logger.warning("blur_level %d > %d -> 상한으로 보정 (%s)",
                       value, MAX_BLUR_LEVEL, src_key)
        return MAX_BLUR_LEVEL
    return value


def to_variant_key(original_key):
    """원본 key로부터 블러본 key를 결정론적으로 계산한다.

    백엔드 ``S3ObjectKeyGenerator.toVariantKey``와 동일한 규칙:
    ``originals/{memberId}/{uuid}.{ext}`` -> ``variants/{memberId}/{uuid}.jpg``.
    """
    if not original_key or not original_key.startswith(ORIGINALS_PREFIX):
        raise ValueError(f"원본 키 형식이 아닙니다: {original_key}")
    relative_path = original_key[len(ORIGINALS_PREFIX):]
    base, dot, _ext = relative_path.rpartition(".")
    # rpartition은 점이 없으면 base="" 를 돌려준다. 백엔드와 동일하게 확장자 없는 키는 거부한다.
    if not dot or not base:
        raise ValueError(f"확장자가 없는 원본 키입니다: {original_key}")
    return f"{VARIANTS_PREFIX}{base}.{VARIANT_EXTENSION}"


def make_blur(image_bytes, blur_level):
    """원본 이미지 바이트를 받아 블러본 JPEG 바이트를 반환한다(순수 함수, S3 무관).

    절차: ① 작게 다운스케일 -> ② 가우시안 블러 -> ③ 원본 크기로 업스케일 -> ④ jpg 저장.
    다운스케일로 고주파 정보를 물리적으로 제거해 디컨볼루션 복원을 차단한다.
    """
    t = (blur_level - MIN_BLUR_LEVEL) / (MAX_BLUR_LEVEL - MIN_BLUR_LEVEL)
    t = min(1.0, max(0.0, t))

    with Image.open(BytesIO(image_bytes)) as image:
        # EXIF 회전 정보를 픽셀에 반영하고, JPEG로 저장하기 위해 RGB로 변환한다(알파 제거).
        image = ImageOps.exif_transpose(image)
        image = image.convert("RGB")
        target_size = _variant_target_size(image.size)

        downscale_px = round(
            DOWNSCALE_LONGEST_SIDE_WEAK
            + t * (DOWNSCALE_LONGEST_SIDE_STRONG - DOWNSCALE_LONGEST_SIDE_WEAK)
        )
        small = _downscale_longest_side(image, downscale_px)

        radius = GAUSSIAN_RADIUS_WEAK + t * (GAUSSIAN_RADIUS_STRONG - GAUSSIAN_RADIUS_WEAK)
        small = small.filter(ImageFilter.GaussianBlur(radius))

        blurred = small.resize(target_size, Image.BILINEAR)

        buffer = BytesIO()
        blurred.save(buffer, format="JPEG", quality=JPEG_QUALITY)
        return buffer.getvalue()


def _variant_target_size(original_size):
    """블러본 출력 크기. 원본 비율을 유지하되 가장 긴 변을 상한으로 캡한다(원본보다 키우지 않음)."""
    width, height = original_size
    longest = max(width, height)
    if longest <= VARIANT_MAX_LONGEST_SIDE:
        return original_size
    scale = VARIANT_MAX_LONGEST_SIDE / longest
    return (max(1, round(width * scale)), max(1, round(height * scale)))


def _downscale_longest_side(image, target_longest_side):
    """가장 긴 변이 target이 되도록 비율을 유지하며 축소한다(이미 더 작으면 그대로)."""
    width, height = image.size
    longest = max(width, height)
    if longest <= target_longest_side:
        return image.copy()
    scale = target_longest_side / longest
    new_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return image.resize(new_size, Image.LANCZOS)


def _client():
    global _s3_client
    if _s3_client is None:
        _s3_client = boto3.client("s3")
    return _s3_client


def _variants_bucket():
    bucket = os.environ.get("VARIANTS_BUCKET")
    if not bucket:
        raise RuntimeError("VARIANTS_BUCKET 환경변수가 설정되지 않았습니다.")
    return bucket
