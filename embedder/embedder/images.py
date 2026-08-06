"""바이트 -> 모델에 넣을 수 있는 RGB 이미지."""

from __future__ import annotations

import io

import pillow_heif
from PIL import Image, ImageOps

# 아이폰 사진이 HEIC로 올라온다. 서버가 image/heic와 image/heif를 허용하고 있으므로
# (PhotoService.ALLOWED_CONTENT_TYPES) 이게 없으면 그 사진들만 조용히 전부 실패한다.
pillow_heif.register_heif_opener()


def load(data: bytes, long_edge: int) -> Image.Image:
    image = Image.open(io.BytesIO(data))

    # EXIF 회전을 픽셀에 굽는다. 세로로 찍은 사진은 파일 안에서는 가로로 누워 있고 방향만
    # 메타데이터에 적혀 있다. 반영하지 않으면 같은 장면을 90도 돌려서 임베딩하는 셈이라
    # 유사도가 실제보다 낮게 나온다.
    image = ImageOps.exif_transpose(image)

    # 팔레트 이미지나 알파 채널이 섞여 들어오면 모델 프로세서가 채널 수에서 깨진다.
    image = image.convert("RGB")

    # 비율을 유지한 채 줄인다. 제자리 연산이라 반환값이 없다.
    image.thumbnail((long_edge, long_edge), Image.Resampling.LANCZOS)
    return image


def to_jpeg(image: Image.Image, quality: int) -> bytes:
    """[load]가 돌려준 이미지를 브라우저가 그릴 수 있는 JPEG 바이트로 만든다.

    별도의 디코딩이나 축소가 없다는 점이 중요하다. HEIC 디코딩도, EXIF 회전 굽기도,
    긴 변 축소도 [load]에서 이미 끝났다 -- 여기 남은 것은 인코딩뿐이다. 미리보기를
    임베딩 잡 안에서 만드는 이유가 이것이다.

    progressive는 목록에서 수십 장이 동시에 뜰 때 위에서부터 차오르게 한다.
    """
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality, optimize=True, progressive=True)
    return buffer.getvalue()


def preview_key_for(storage_key: str) -> str:
    """원본 키에서 파생본 키를 만든다.

    `galleries/1/{uuid}.heic` -> `previews/galleries/1/{uuid}.jpg`

    원본 키를 그대로 접두사 아래에 붙이므로 둘의 대응이 눈으로 보이고, 접두사 하나로
    수명 주기 규칙이나 일괄 삭제를 걸 수 있다. 확장자는 원본이 무엇이었든 jpg다.
    """
    stem = storage_key.rsplit(".", 1)[0]
    return f"previews/{stem}.jpg"
