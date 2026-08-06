"""photos 테이블 읽기/쓰기.

스키마는 앱(Flyway)이 소유한다. 이 모듈이 건드리는 것은 네 컬럼뿐이다 --
`embedding`, `preview_key`, `status`, `updated_at`.

접속은 원래 **RDS IAM 인증 토큰**을 썼다. 토큰 생성(`generate_db_auth_token`)은 로컬 서명
연산이라 네트워크를 타지 않는다 -- NAT도 인터페이스 엔드포인트도 없는 이 서브넷에서
자격증명을 얻을 수 있는 유일한 방법이었고, 덕분에 비밀번호가 어디에도 남지 않았다.

**지금은 비밀번호를 쓴다.** 조직 SCP가 `rds-db:connect`를 계정 전체에서 거부하기 때문이다.
이 계정은 조직의 멤버 계정이라 여기서는 풀 수 없다. 원복 절차는 인프라 레포
`docs/runbook.md`의 "SCP 차단" 절에 있다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np
import psycopg
from pgvector.psycopg import register_vector

from embedder.config import Settings

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class PhotoRef:
    photo_id: int
    storage_key: str


def connect(settings: Settings) -> psycopg.Connection:
    # SCP가 풀리면 아래 password 인자를 지우고 이 토큰 생성으로 되돌린다 (import boto3 필요):
    #
    #     token = boto3.client("rds").generate_db_auth_token(
    #         DBHostname=settings.db_auth_host,
    #         Port=settings.db_port,
    #         DBUsername=settings.db_user,
    #     )
    #
    # 그때 DB 쪽에서 `GRANT rds_iam TO embedder;`도 함께 해줘야 한다. 반대로 지금은 그 GRANT가
    # 있으면 안 된다 -- pg_hba가 `hostssl all +rds_iam pam`을 먼저 매칭해서 비밀번호를 아예
    # 보지 않고 PAM으로 보낸다. 그 상태의 증상은 `PAM authentication failed`다.
    connection = psycopg.connect(
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
        # TLS는 IAM 인증 때문만이 아니다. RDS PostgreSQL 15+ 는 rds.force_ssl이 기본 1이라
        # 평문 접속 자체를 거부한다. 비밀번호로 바뀐 지금도 그대로 필요하다.
        sslmode=settings.db_sslmode,
        sslrootcert=settings.db_sslrootcert,
        connect_timeout=10,
    )

    # 이걸 해야 파이썬 리스트/ndarray를 vector 컬럼에 그대로 바인딩할 수 있다. 없으면
    # '[0.1,0.2,...]' 문자열을 손으로 조립하게 되는데, 표기가 조금만 어긋나도 예외가 아니라
    # 파싱 실패로 나타난다.
    register_vector(connection)
    return connection


def fetch_targets(connection: psycopg.Connection, gallery_id: int, force: bool) -> list[PhotoRef]:
    """이번 실행이 처리할 사진.

    기본값은 아직 임베딩이 없는 것만 고른다. 그래서 중간에 죽은 실행을 다시 부르면 남은 것만
    이어서 처리하고, 재시도 로직을 따로 짤 필요가 없다. force는 모델이나 전처리를 바꿔 전량
    다시 계산할 때만 쓴다.

    PENDING은 건너뛴다 -- 업로드 URL만 발급되고 S3에 객체가 아직 없을 수 있는 상태다.
    """
    sql = """
        SELECT id, storage_key
        FROM photos
        WHERE gallery_id = %s
          AND status <> 'PENDING'
    """
    if not force:
        sql += " AND embedding IS NULL"
    sql += " ORDER BY id"

    with connection.cursor() as cursor:
        cursor.execute(sql, (gallery_id,))
        return [PhotoRef(photo_id=row[0], storage_key=row[1]) for row in cursor.fetchall()]


def store_embeddings(
    connection: psycopg.Connection,
    results: Iterable[tuple[PhotoRef, np.ndarray, str | None]],
) -> int:
    """계산된 벡터와 파생본 위치를 배치로 적재한다.

    벡터 차원은 vector(n) 컬럼이 강제한다. 모델을 바꿔 폭이 달라지면 여기서 DB 에러로
    떨어진다 -- 조용히 틀린 값이 들어가지 않는다는 뜻이라 굳이 앞단에서 또 막지 않는다.

    preview_key를 벡터와 같은 UPDATE에 쓰는 것이 중요하다. 따로 쓰면 "벡터는 있는데
    미리보기는 없는" 중간 상태가 생기고, 그 상태를 프론트가 구분할 방법이 없다.

    COALESCE인 이유: 이번 실행에서 파생본 업로드만 실패하면 preview_key가 None으로
    오는데, 그때 이전 실행이 남긴 멀쩡한 값을 지우면 안 된다.
    """
    rows: Sequence[tuple] = [
        (vector, preview_key, ref.photo_id) for ref, vector, preview_key in results
    ]
    if not rows:
        return 0

    with connection.cursor() as cursor:
        cursor.executemany(
            """
            UPDATE photos
            SET embedding = %s,
                preview_key = COALESCE(%s, preview_key),
                status = 'EMBEDDED',
                updated_at = now()
            WHERE id = %s
            """,
            rows,
        )
    return len(rows)
