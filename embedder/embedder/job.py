"""갤러리 하나의 임베딩을 계산해 적재한다.

진입점(`handler.py` / `__main__.py`)이 둘이고 본체는 이 함수 하나다. Lambda로 감싸기 전에
로컬에서 실제 S3·RDS를 상대로 같은 코드를 검증할 수 있어야 해서 이렇게 갈라 두었다.
나중에 Fargate로 옮겨도 바뀌는 것은 진입점뿐이다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

from embedder import db, images, model
from embedder.config import Settings
from embedder.storage import PhotoStorage

log = logging.getLogger(__name__)


@dataclass
class RunResult:
    gallery_id: int
    targets: int = 0
    processed: int = 0
    failed: list[str] = field(default_factory=list)
    elapsed_seconds: float = 0.0

    def to_dict(self) -> dict:
        return {
            "galleryId": self.gallery_id,
            "targets": self.targets,
            "processed": self.processed,
            "failed": self.failed,
            "elapsedSeconds": round(self.elapsed_seconds, 1),
        }


def run(gallery_id: int, force: bool = False, settings: Settings | None = None) -> dict:
    started = time.monotonic()
    settings = settings or Settings.from_env()
    result = RunResult(gallery_id=gallery_id)

    storage = PhotoStorage(settings.s3_bucket)
    embedder = model.load_from(settings)

    with db.connect(settings) as connection:
        targets = db.fetch_targets(connection, gallery_id, force)
        result.targets = len(targets)
        log.info("갤러리 %s: 대상 %s장 (force=%s)", gallery_id, len(targets), force)

        for batch in _chunked(targets, settings.batch_size):
            loaded_refs = []
            loaded_images = []

            for ref in batch:
                try:
                    data = storage.read(ref.storage_key)
                    loaded_images.append(images.load(data, settings.resize_long_edge))
                    loaded_refs.append(ref)
                except Exception:
                    # 한 장이 잡 전체를 죽이지 않게 한다. 실패한 사진은 embedding이 NULL로
                    # 남으므로, 다시 호출하면 fetch_targets가 자연히 다시 집어 온다.
                    log.exception("사진을 읽지 못했습니다: %s", ref.storage_key)
                    result.failed.append(ref.storage_key)

            if not loaded_images:
                continue

            batch_started = time.monotonic()
            vectors = embedder.encode(loaded_images)
            stored = db.store_embeddings(connection, zip(loaded_refs, vectors))

            # 배치 단위로 커밋한다. 15분 타임아웃에 걸려 중간에 끊겨도 여기까지는 남고,
            # 다음 호출이 남은 것만 이어서 한다.
            connection.commit()
            result.processed += stored

            per_photo = (time.monotonic() - batch_started) / len(loaded_images)
            log.info("진행 %s/%s (장당 %.2fs)", result.processed, result.targets, per_photo)

    result.elapsed_seconds = time.monotonic() - started
    log.info("완료: %s", result.to_dict())
    return result.to_dict()


def _chunked(items: list, size: int):
    for start in range(0, len(items), size):
        yield items[start:start + size]
