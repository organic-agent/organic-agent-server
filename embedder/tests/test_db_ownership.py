from __future__ import annotations

import sys
import types
import unittest
from pathlib import Path


# 이 테스트는 Lambda image 밖의 서버 CI에서도 SQL 계약을 확인할 수 있어야 한다. psycopg와
# pgvector가 설치되지 않은 환경에서는 import 표면만 대체하고, 실제 쿼리 실행은 아래 cursor
# fake가 기록한다. 운영 image에서는 requirements의 진짜 모듈이 그대로 사용된다.
EMBEDDER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EMBEDDER_ROOT))

try:
    import psycopg  # noqa: F401
except ModuleNotFoundError:
    psycopg_module = types.ModuleType("psycopg")
    psycopg_module.Connection = object
    psycopg_module.connect = lambda **_: None
    sys.modules["psycopg"] = psycopg_module

try:
    from pgvector.psycopg import register_vector  # noqa: F401
except ModuleNotFoundError:
    pgvector_module = types.ModuleType("pgvector")
    pgvector_psycopg_module = types.ModuleType("pgvector.psycopg")
    pgvector_psycopg_module.register_vector = lambda _: None
    pgvector_module.psycopg = pgvector_psycopg_module
    sys.modules["pgvector"] = pgvector_module
    sys.modules["pgvector.psycopg"] = pgvector_psycopg_module

try:
    import numpy  # noqa: F401
except ModuleNotFoundError:
    sys.modules["numpy"] = types.ModuleType("numpy")

# db.py는 PhotoMetadata의 타입과 필드만 사용한다. 실제 metadata 모듈은 import 시 Pillow를
# 요구하므로 DB SQL 단위 테스트가 이미지 처리 의존성까지 설치하게 만들지 않는다.
metadata_module = types.ModuleType("embedder.metadata")
metadata_module.PhotoMetadata = type("PhotoMetadata", (), {})
sys.modules["embedder.metadata"] = metadata_module

from embedder import db


class RecordingCursor:
    def __init__(self, rows: list[tuple] | None = None):
        self.rows = rows or []
        self.executed: tuple[str, tuple] | None = None
        self.executed_many: tuple[str, list[tuple]] | None = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, sql: str, params: tuple):
        self.executed = (sql, params)

    def executemany(self, sql: str, params: list[tuple]):
        self.executed_many = (sql, params)

    def fetchall(self):
        return self.rows


class RecordingConnection:
    def __init__(self, rows: list[tuple] | None = None):
        self.recording_cursor = RecordingCursor(rows)

    def cursor(self):
        return self.recording_cursor


class DbOwnershipTest(unittest.TestCase):
    def test_fetch_targets_excludes_shared_template_for_force_and_normal_runs(self):
        for force in (False, True):
            with self.subTest(force=force):
                connection = RecordingConnection([(7, "galleries/3/photo.jpg")])

                targets = db.fetch_targets(connection, gallery_id=3, force=force)

                sql, params = connection.recording_cursor.executed
                self.assertIn("storage_ownership = 'GALLERY'", sql)
                self.assertIn("status <> 'PENDING'", sql)
                self.assertEqual("embedding IS NULL" in sql, not force)
                self.assertEqual((3,), params)
                self.assertEqual([db.PhotoRef(7, "galleries/3/photo.jpg")], targets)

    def test_store_embeddings_cannot_update_shared_template_row(self):
        connection = RecordingConnection()
        result = (
            db.PhotoRef(9, "galleries/3/photo.jpg"),
            object(),
            "previews/galleries/3/photo.jpg",
            None,
        )

        stored = db.store_embeddings(connection, [result])

        sql, rows = connection.recording_cursor.executed_many
        self.assertIn("WHERE id = %s", sql)
        self.assertIn("storage_ownership = 'GALLERY'", sql)
        self.assertEqual(9, rows[0][-1])
        self.assertEqual(1, stored)


if __name__ == "__main__":
    unittest.main()
