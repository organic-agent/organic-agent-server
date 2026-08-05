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
