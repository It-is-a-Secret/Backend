"""블러 생성 Lambda 단위 테스트.

S3·AWS 없이 동작하도록 순수 함수(``resolve_blur_level``/``to_variant_key``/``make_blur``)를
직접 검증하고, 핸들러는 S3 클라이언트를 가짜로 주입해 확인한다. 샘플 이미지는 바이너리를
커밋하지 않고 Pillow로 즉석 생성한다.
"""

import logging
import sys
from io import BytesIO
from pathlib import Path

import pytest
from PIL import Image

# tests/ 의 부모(blur/)를 import 경로에 추가해 handler 모듈을 불러온다.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import handler  # noqa: E402


# --- 샘플 이미지 ----------------------------------------------------------------

def _checkerboard_png(size=128, cell=8):
    """고주파(선명한 경계)가 많은 체커보드 이미지. 블러 강도 비교에 적합하다."""
    image = Image.new("RGB", (size, size), "white")
    pixels = image.load()
    for y in range(size):
        for x in range(size):
            if (x // cell + y // cell) % 2 == 0:
                pixels[x, y] = (0, 0, 0)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def _solid_png(width, height):
    """단색 이미지. 출력 크기(캡) 검증처럼 내용이 중요하지 않을 때 쓴다."""
    image = Image.new("RGB", (width, height), (120, 120, 120))
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def _total_variation(jpeg_bytes):
    """인접 픽셀 차이의 총합. 블러가 강할수록(고주파가 적을수록) 작아진다."""
    with Image.open(BytesIO(jpeg_bytes)) as image:
        gray = image.convert("L")
        width, height = gray.size
        data = list(gray.getdata())
    total = 0
    for y in range(height):
        row = y * width
        for x in range(1, width):
            total += abs(data[row + x] - data[row + x - 1])
    return total


# --- resolve_blur_level (clamp 정책) -------------------------------------------

@pytest.mark.parametrize("raw, expected", [
    ("70", 70),            # 정상 값
    (None, 80),            # 누락 -> 기본값
    ("not-a-number", 80),  # 파싱 불가 -> 기본값
    ("49", 50),            # 하한 미만 -> 50
    ("0", 50),
    ("101", 100),          # 상한 초과 -> 100
    ("50", 50),            # 경계
    ("100", 100),          # 경계
])
def test_resolve_blur_level_clamps(raw, expected):
    assert handler.resolve_blur_level(raw) == expected


def test_resolve_blur_level_warns_on_adjust(caplog):
    with caplog.at_level(logging.WARNING):
        handler.resolve_blur_level(None)
        handler.resolve_blur_level("9999")
    assert len(caplog.records) == 2


# --- to_variant_key (백엔드 키 규칙 일치) ---------------------------------------

@pytest.mark.parametrize("original, variant", [
    ("originals/100/11111111-1111-1111-1111-111111111111.png",
     "variants/100/11111111-1111-1111-1111-111111111111.jpg"),
    ("originals/7/abc-123.jpeg", "variants/7/abc-123.jpg"),
])
def test_to_variant_key_matches_backend_rule(original, variant):
    assert handler.to_variant_key(original) == variant


@pytest.mark.parametrize("bad_key", [
    "variants/1/x.jpg",       # originals prefix 아님
    "originals/1/noext",      # 확장자 없음
    "",                       # 빈 문자열
    None,                     # null
])
def test_to_variant_key_rejects_invalid(bad_key):
    with pytest.raises(ValueError):
        handler.to_variant_key(bad_key)


# --- make_blur ------------------------------------------------------------------

def test_make_blur_outputs_valid_jpeg_with_same_dimensions():
    source = _checkerboard_png(size=128)

    result = handler.make_blur(source, 80)

    with Image.open(BytesIO(result)) as image:
        assert image.format == "JPEG"
        # 상한(1080)보다 작은 원본은 크기를 유지한다.
        assert image.size == (128, 128)


def test_make_blur_caps_longest_side_for_large_original(monkeypatch):
    monkeypatch.setattr(handler, "VARIANT_MAX_LONGEST_SIDE", 1080)
    # 2000x1000 원본 -> 가장 긴 변 1080으로 캡, 비율 유지 -> 1080x540.
    source = _solid_png(2000, 1000)

    result = handler.make_blur(source, 80)

    with Image.open(BytesIO(result)) as image:
        assert max(image.size) == 1080
        assert image.size == (1080, 540)


def test_higher_blur_level_reduces_detail():
    """blur_level 50 < 80 < 100 순으로 디테일(total variation)이 줄어야 한다."""
    source = _checkerboard_png(size=128)

    tv_50 = _total_variation(handler.make_blur(source, 50))
    tv_80 = _total_variation(handler.make_blur(source, 80))
    tv_100 = _total_variation(handler.make_blur(source, 100))

    assert tv_100 <= tv_80 <= tv_50
    # 가장 약한 블러도 원본 체커보드 대비 디테일을 충분히 제거해야 한다.
    assert tv_50 < _total_variation(source)


# --- lambda_handler (가짜 S3 클라이언트) ----------------------------------------

class _FakeS3Client:
    def __init__(self, objects):
        self._objects = objects  # {(bucket, key): {"Body":bytes, "Metadata":{...}}}
        self.put_calls = []

    def get_object(self, Bucket, Key):
        obj = self._objects[(Bucket, Key)]
        return {"Body": BytesIO(obj["Body"]), "Metadata": obj.get("Metadata", {})}

    def put_object(self, Bucket, Key, Body, ContentType):
        self.put_calls.append({
            "Bucket": Bucket, "Key": Key, "Body": Body, "ContentType": ContentType,
        })


def _s3_event(bucket, key):
    return {"Records": [{"s3": {"bucket": {"name": bucket}, "object": {"key": key}}}]}


def test_lambda_handler_reads_original_and_writes_variant(monkeypatch):
    src_key = "originals/100/11111111-1111-1111-1111-111111111111.png"
    fake = _FakeS3Client({
        ("originals-bucket", src_key): {
            "Body": _checkerboard_png(64),
            "Metadata": {"blur-level": "100"},
        }
    })
    monkeypatch.setattr(handler, "_s3_client", fake)
    monkeypatch.setenv("VARIANTS_BUCKET", "variants-bucket")

    handler.lambda_handler(_s3_event("originals-bucket", src_key), None)

    assert len(fake.put_calls) == 1
    call = fake.put_calls[0]
    assert call["Bucket"] == "variants-bucket"
    assert call["Key"] == "variants/100/11111111-1111-1111-1111-111111111111.jpg"
    assert call["ContentType"] == "image/jpeg"
    with Image.open(BytesIO(call["Body"])) as image:
        assert image.format == "JPEG"


def test_lambda_handler_url_decodes_object_key(monkeypatch):
    # S3 이벤트의 key는 URL 인코딩되어 온다(공백 -> '+').
    encoded_key = "originals/100/photo+with+space.png"
    decoded_key = "originals/100/photo with space.png"
    fake = _FakeS3Client({
        ("originals-bucket", decoded_key): {
            "Body": _checkerboard_png(32),
            "Metadata": {},  # blur-level 누락 -> 기본값 80
        }
    })
    monkeypatch.setattr(handler, "_s3_client", fake)
    monkeypatch.setenv("VARIANTS_BUCKET", "variants-bucket")

    handler.lambda_handler(_s3_event("originals-bucket", encoded_key), None)

    assert fake.put_calls[0]["Key"] == "variants/100/photo with space.jpg"


def test_lambda_handler_skips_non_original_key(monkeypatch, caplog):
    # originals/ prefix가 아닌 객체는 실패 없이 건너뛴다(get/put 모두 호출 안 함).
    fake = _FakeS3Client({})
    monkeypatch.setattr(handler, "_s3_client", fake)
    monkeypatch.setenv("VARIANTS_BUCKET", "variants-bucket")

    with caplog.at_level(logging.WARNING):
        handler.lambda_handler(_s3_event("originals-bucket", "variants/100/x.jpg"), None)

    assert fake.put_calls == []
    assert any("prefix" in record.message for record in caplog.records)
