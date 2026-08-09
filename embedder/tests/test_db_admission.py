from __future__ import annotations

import sys
import types
import unittest


# admission SQL 테스트는 이미지·벡터·DB 드라이버를 실행하지 않는다. Lambda 의존성을 전부
# 설치하지 않은 로컬/CI에서도 이 경계를 검증할 수 있도록 import 자리만 최소 대체한다.
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

    def fetchone(self):
        return self.connection.rows.pop(0)


class _Connection:
    def __init__(self, rows: list[tuple | None]):
        self.rows = rows
        self.executed: list[tuple[str, tuple]] = []

    def cursor(self) -> _Cursor:
        return _Cursor(self)


class StudioWriteAdmissionTest(unittest.TestCase):
    def test_shared_lock_is_held_until_admitted_job_body_finishes(self) -> None:
        connection = _Connection(rows=[(42,), (True,)])

        with db.studio_write_admission(connection, gallery_id=7) as admitted:
            self.assertTrue(admitted)
            self.assertEqual(
                [
                    ("SELECT studio_id FROM galleries WHERE id = %s", (7,)),
                    ("SELECT pg_advisory_lock_shared(%s)", (-(1 << 63) + 42,)),
                    (
                        "SELECT NOT EXISTS (SELECT 1 FROM studio_deletion_claims WHERE studio_id = %s)",
                        (42,),
                    ),
                ],
                connection.executed,
            )

        self.assertEqual(
            ("SELECT pg_advisory_unlock_shared(%s)", (-(1 << 63) + 42,)),
            connection.executed[-1],
        )

    def test_claim_is_checked_after_shared_lock_and_unlocks_without_work(self) -> None:
        connection = _Connection(rows=[(42,), (False,)])

        with db.studio_write_admission(connection, gallery_id=7) as admitted:
            self.assertFalse(admitted)

        self.assertEqual(
            [
                ("SELECT studio_id FROM galleries WHERE id = %s", (7,)),
                ("SELECT pg_advisory_lock_shared(%s)", (-(1 << 63) + 42,)),
                (
                    "SELECT NOT EXISTS (SELECT 1 FROM studio_deletion_claims WHERE studio_id = %s)",
                    (42,),
                ),
                ("SELECT pg_advisory_unlock_shared(%s)", (-(1 << 63) + 42,)),
            ],
            connection.executed,
        )

    def test_no_gallery_does_not_acquire_a_lock(self) -> None:
        connection = _Connection(rows=[None])

        with db.studio_write_admission(connection, gallery_id=404) as admitted:
            self.assertFalse(admitted)

        self.assertEqual(
            [("SELECT studio_id FROM galleries WHERE id = %s", (404,))],
            connection.executed,
        )

    def test_positive_studio_id_uses_negative_64_bit_namespace(self) -> None:
        self.assertEqual(-(1 << 63) + 1, db._studio_write_fence_key(1))
        self.assertEqual(-1, db._studio_write_fence_key((1 << 63) - 1))
        with self.assertRaises(ValueError):
            db._studio_write_fence_key(0)


if __name__ == "__main__":
    unittest.main()
