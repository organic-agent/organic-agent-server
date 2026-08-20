from __future__ import annotations

import sys
import types
import unittest
from pathlib import Path


# 대상 선별 SQL 테스트는 이미지·벡터·DB 드라이버를 실행하지 않는다. Lambda 의존성을 전부
# 설치하지 않은 로컬/CI에서도 이 경계를 검증할 수 있도록 import 자리만 최소 대체한다.
EMBEDDER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EMBEDDER_ROOT))

try:
    import numpy  # noqa: F401
except ModuleNotFoundError:
    sys.modules["numpy"] = types.ModuleType("numpy")

try:
    import psycopg  # noqa: F401
except ModuleNotFoundError:
    psycopg_module = types.ModuleType("psycopg")
    psycopg_module.Error = RuntimeError
    sys.modules["psycopg"] = psycopg_module

try:
    from pgvector.psycopg import register_vector  # noqa: F401
except ModuleNotFoundError:
    pgvector_package = types.ModuleType("pgvector")
    pgvector_package.__path__ = []
    pgvector_psycopg = types.ModuleType("pgvector.psycopg")
    pgvector_psycopg.register_vector = lambda connection: None
    sys.modules["pgvector"] = pgvector_package
    sys.modules["pgvector.psycopg"] = pgvector_psycopg

metadata_module = types.ModuleType("embedder.metadata")
metadata_module.PhotoMetadata = type("PhotoMetadata", (), {})
sys.modules.setdefault("embedder.metadata", metadata_module)

from embedder import db


class _Cursor:
    def __init__(self, connection: "_Connection"):
        self.connection = connection

    def __enter__(self) -> "_Cursor":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        return None

    def execute(self, sql: str, params: tuple) -> None:
        self.connection.executed.append((" ".join(sql.split()), params))

    def fetchall(self):
        return self.connection.rows


class _Connection:
    def __init__(self, rows: list[tuple]):
        self.rows = rows
        self.executed: list[tuple[str, tuple]] = []

    def cursor(self) -> _Cursor:
        return _Cursor(self)


class FetchTargetsTest(unittest.TestCase):
    """앱의 휴지통(deleted_at)·업로드 상태와 맞물리는 선별 조건이 SQL에 남아 있는지 지킨다.

    V17이 studio_deletion_claims를 DROP했을 때 Lambda가 그 테이블을 계속 읽어 전량 실패한 적이
    있다. 스키마 계약이 바뀌면 이 테스트가 먼저 깨져야 한다.
    """

    def test_skips_pending_and_trashed_rows(self) -> None:
        connection = _Connection(rows=[(1, "galleries/7/a.jpg"), (2, "galleries/7/b.jpg")])

        targets = db.fetch_targets(connection, gallery_id=7, force=False)

        sql, params = connection.executed[0]
        self.assertEqual((7,), params)
        self.assertIn("JOIN galleries g ON g.id = p.gallery_id", sql)
        self.assertIn("p.status <> 'PENDING'", sql)
        self.assertIn("p.deleted_at IS NULL", sql)
        self.assertIn("g.deleted_at IS NULL", sql)
        self.assertNotIn("studio_deletion_claims", sql)
        self.assertEqual(
            [db.PhotoRef(1, "galleries/7/a.jpg"), db.PhotoRef(2, "galleries/7/b.jpg")],
            targets,
        )

    def test_default_run_only_picks_photos_without_embedding(self) -> None:
        connection = _Connection(rows=[])

        db.fetch_targets(connection, gallery_id=7, force=False)

        sql, _ = connection.executed[0]
        self.assertIn("p.embedding IS NULL", sql)
        self.assertTrue(sql.endswith("ORDER BY p.id"))

    def test_force_recomputes_everything(self) -> None:
        connection = _Connection(rows=[])

        db.fetch_targets(connection, gallery_id=7, force=True)

        sql, _ = connection.executed[0]
        self.assertNotIn("embedding IS NULL", sql)


if __name__ == "__main__":
    unittest.main()
