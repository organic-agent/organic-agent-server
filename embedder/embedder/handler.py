"""Lambda 진입점.

앱이 `POST /api/v1/galleries/{id}/embeddings/run`을 받으면 이 함수를 EVENT(비동기)로 부른다.
페이로드는 `{"galleryId": 1, "force": false}`.
"""

from __future__ import annotations

import logging

from embedder import job, model
from embedder.config import Settings

logging.getLogger().setLevel(logging.INFO)

# 초기화 단계(모듈 임포트)에서 모델을 올려 둔다. 핸들러 안에서 로드하면 웜 스타트에도 매번
# 다시 읽고, Lambda가 초기화 구간에 더 넉넉히 주는 CPU도 못 쓴다.
_SETTINGS = Settings.from_env()
model.load_from(_SETTINGS)


def handler(event: dict, context) -> dict:
    gallery_id = event.get("galleryId")
    if gallery_id is None:
        raise ValueError("페이로드에 galleryId가 없습니다")

    return job.run(
        gallery_id=int(gallery_id),
        force=bool(event.get("force", False)),
        settings=_SETTINGS,
    )
