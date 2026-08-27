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
from embedder.admin_event import AdminPhotoEvent


class _Cursor:
    def __init__(self, connection: "_Connection"):
        self.connection = connection

    def __enter__(self) -> "_Cursor":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        return None

    def execute(self, sql: str, params: tuple) -> None:
        self.connection.executed.append((" ".join(sql.split()), params))
        self.rowcount = self.connection.next_rowcount()

    def executemany(self, sql: str, params) -> None:
        rows = tuple(params)
        self.connection.executed.append((" ".join(sql.split()), rows))
        self.rowcount = self.connection.next_rowcount()

    def fetchall(self):
        return self.connection.rows

    def fetchone(self):
        return self.connection.rows[0] if self.connection.rows else None


class _Connection:
    def __init__(self, rows: list[tuple], rowcounts: list[int] | None = None):
        self.rows = rows
        self.executed: list[tuple[str, tuple]] = []
        self.rowcounts = iter(rowcounts or [])

    def cursor(self) -> _Cursor:
        return _Cursor(self)

    def next_rowcount(self) -> int:
        return next(self.rowcounts, len(self.rows))


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

    def test_store_cas_uses_fetched_storage_key_and_active_resource_boundaries(self) -> None:
        connection = _Connection(rows=[], rowcounts=[1])
        ref = db.PhotoRef(17, "galleries/7/original-before-replacement.jpg")

        stored = db.store_embeddings(connection, [(ref, [0.1, 0.2], None, None)])

        self.assertEqual(1, stored)
        sql, rows = connection.executed[0]
        self.assertIn("WHERE id = %s AND storage_key = %s AND deleted_at IS NULL", sql)
        self.assertIn("g.id = photos.gallery_id AND g.deleted_at IS NULL", sql)
        self.assertEqual(17, rows[0][-2])
        self.assertEqual("galleries/7/original-before-replacement.jpg", rows[0][-1])

    def test_store_ignores_stale_photo_replaced_after_fetch(self) -> None:
        connection = _Connection(rows=[], rowcounts=[0])
        old_ref = db.PhotoRef(17, "galleries/7/old.jpg")

        stored = db.store_embeddings(connection, [(old_ref, [0.1, 0.2], "previews/old.jpg", None)])

        self.assertEqual(0, stored)


class AdminPhotoJobDatabaseContractTest(unittest.TestCase):
    def event(self) -> AdminPhotoEvent:
        return AdminPhotoEvent(11, 2, "QUALITY_ANALYSIS", 31, 41, "galleries/41/photo.jpg", 51)

    def test_verification_cas_includes_job_attempt_revision_and_storage_key(self) -> None:
        connection = _Connection(rows=[(1,)])

        self.assertTrue(db.verify_admin_photo_event(connection, self.event()))

        sql, params = connection.executed[0]
        self.assertIn("j.attempt_count = %s", sql)
        self.assertIn("j.revision_id = %s", sql)
        self.assertIn("p.storage_key = %s", sql)
        self.assertIn("r.storage_key = %s", sql)
        self.assertEqual(11, params[0])
        self.assertEqual(2, params[1])

    def test_photo_result_and_job_success_are_both_required_before_commit(self) -> None:
        connection = _Connection(rows=[], rowcounts=[1, 0])
        result = type("Quality", (), {"score": 82.5, "signals": {"algorithmVersion": "technical-v1"}})()

        with self.assertRaises(db.AdminJobClaimLost):
            db.complete_admin_quality(connection, self.event(), result)

        self.assertEqual(2, len(connection.executed))
        self.assertIn("technical_quality_score", connection.executed[0][0])
        self.assertIn("status = 'SUCCEEDED'", connection.executed[1][0])

    def test_explicit_failure_only_updates_matching_exact_attempt(self) -> None:
        connection = _Connection(rows=[], rowcounts=[1])

        updated = db.fail_admin_photo_job(connection, self.event(), "NO_SUCH_KEY")

        self.assertEqual(1, updated)
        sql, params = connection.executed[0]
        self.assertIn("attempt_count = %s", sql)
        self.assertIn("revision_id = %s", sql)
        self.assertIn("status IN ('DISPATCHING', 'DISPATCHED')", sql)
        self.assertEqual("NO_SUCH_KEY", params[0])


if __name__ == "__main__":
    unittest.main()
