#!/usr/bin/env python3
"""클러스터링 평가셋 export — 라벨링 템플릿 CSV 생성과 fixture JSON 생성.

이미지 바이트는 다루지 않는다. 클러스터링 입력은 임베딩과 촬영 시각뿐이므로,
사람이 채운 정답 라벨 CSV와 DB의 임베딩·taken_at을 합쳐 테스트 fixture를 만든다.
라벨링 기준과 전체 절차는 src/test/resources/eval/README.md 참조.

사용:
  scripts/db-tunnel.sh                    # 원격 DB 기준. 비밀번호가 클립보드에 복사된다

  # 1) 라벨링 템플릿 (촬영 시각순 — 연사가 붙어 나온다)
  PGPASSWORD=$(pbpaste) scripts/export-eval-set.py template --gallery-id 123

  # 2) 사람이 eval-labels-123.csv의 group_label 열을 채운 뒤 fixture 생성
  PGPASSWORD=$(pbpaste) scripts/export-eval-set.py fixture \\
      --gallery-id 123 --labels eval-labels-123.csv

로컬 개발 DB라면 --host localhost --port 5432 등으로 접속 정보를 바꾼다.
psql이 필요하다 (DB 드라이버 의존성 대신 psql 서브프로세스를 쓴다).
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path

# PhotoAnalysis.EMBEDDING_DIMENSION과 같은 값이다. 다르면 fixture 생성 시 실패시킨다.
EMBEDDING_DIMENSION = 768

# psql 출력에서 SQL NULL을 구분하기 위한 대체 문자열 (파일명·라벨에 나올 수 없는 값)
NULL_MARKER = "\\N"


def main() -> int:
    args = parse_args()
    if args.command == "template":
        return export_template(args)
    return export_fixture(args)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="클러스터링 평가셋 export")
    sub = parser.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--gallery-id", required=True, type=int, help="평가 대상 갤러리 id")
    common.add_argument("--host", default="localhost")
    common.add_argument("--port", default="15432", help="기본 15432 (db-tunnel.sh)")
    common.add_argument("--dbname", default="wes_db")
    common.add_argument("--user", default="wes_admin")

    template = sub.add_parser("template", parents=[common], help="라벨링 템플릿 CSV 생성")
    template.add_argument("--output", type=Path, help="기본 eval-labels-<갤러리id>.csv")

    fixture = sub.add_parser("fixture", parents=[common], help="라벨 CSV + DB → fixture JSON")
    fixture.add_argument("--labels", required=True, type=Path, help="group_label을 채운 CSV")
    fixture.add_argument(
        "--output", type=Path,
        help="기본 src/test/resources/eval/gallery-<갤러리id>.json (repo 루트 기준)",
    )
    return parser.parse_args()


def export_template(args: argparse.Namespace) -> int:
    rows = query(args, f"""
        SELECT id, original_file_name, taken_at, display_order
        FROM photos
        WHERE gallery_id = {args.gallery_id} AND deleted_at IS NULL
        ORDER BY taken_at NULLS LAST, display_order, id
    """)
    if not rows:
        print(f"오류: 갤러리 {args.gallery_id} 에 사진이 없다", file=sys.stderr)
        return 1

    output = args.output or Path(f"eval-labels-{args.gallery_id}.csv")
    with output.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["photo_id", "file_name", "taken_at", "display_order", "group_label"])
        for photo_id, file_name, taken_at, display_order in rows:
            writer.writerow([photo_id, file_name, none_to_empty(taken_at), display_order, ""])

    print(f"템플릿 생성: {output} ({len(rows)}장)")
    print("group_label 열을 채운 뒤 fixture 명령으로 넘어간다. 단독 사진은 비워 둔다.")
    return 0


def export_fixture(args: argparse.Namespace) -> int:
    labels = read_labels(args.labels)

    rows = query(args, f"""
        SELECT p.id, p.original_file_name, p.taken_at, a.embedding::text
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = {args.gallery_id} AND p.deleted_at IS NULL
        ORDER BY p.taken_at NULLS LAST, p.id
    """)
    by_id = {int(r[0]): r for r in rows}

    missing = sorted(set(labels) - set(by_id))
    if missing:
        print(f"오류: 라벨 CSV에는 있는데 갤러리에 없는 photo_id: {missing}", file=sys.stderr)
        return 1
    unlabeled = sorted(set(by_id) - set(labels))
    if unlabeled:
        # 라벨 파일이 옛 템플릿이면 이후 업로드된 사진이 빠질 수 있다. 조용히 넘어가지 않는다.
        print(f"오류: 갤러리에는 있는데 라벨 CSV에 없는 photo_id: {unlabeled}", file=sys.stderr)
        print("템플릿을 다시 생성해 라벨을 채울 것.", file=sys.stderr)
        return 1

    photos = []
    for photo_id in sorted(by_id):
        _, file_name, taken_at, embedding_text = by_id[photo_id]
        if embedding_text is None:
            print(f"오류: photo {photo_id} 는 아직 임베딩이 없다. AI 분석(ai-analysis) 후 재시도.", file=sys.stderr)
            return 1
        embedding = json.loads(embedding_text)  # pgvector 텍스트 표현 "[0.1,...]"은 JSON과 호환된다
        if len(embedding) != EMBEDDING_DIMENSION:
            print(f"오류: photo {photo_id} 임베딩 차원 {len(embedding)} ≠ {EMBEDDING_DIMENSION}", file=sys.stderr)
            return 1
        photos.append({
            "photoId": photo_id,
            "fileName": file_name,
            "takenAt": taken_at.replace(" ", "T") if taken_at else None,
            "groupLabel": labels[photo_id],
            "embedding": embedding,
        })

    output = args.output or default_fixture_path(args.gallery_id)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w") as f:
        json.dump(
            {"galleryId": args.gallery_id, "embeddingDimension": EMBEDDING_DIMENSION, "photos": photos},
            f, ensure_ascii=False, separators=(",", ":"),
        )
        f.write("\n")

    groups = {label for label in labels.values() if label is not None}
    singletons = sum(1 for label in labels.values() if label is None)
    print(f"fixture 생성: {output} ({len(photos)}장, 그룹 {len(groups)}개, 싱글턴 {singletons}장)")
    return 0


def read_labels(path: Path) -> dict[int, str | None]:
    labels: dict[int, str | None] = {}
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            label = row["group_label"].strip()
            labels[int(row["photo_id"])] = label or None
    if not labels:
        raise SystemExit(f"오류: {path} 가 비어 있다")
    return labels


def default_fixture_path(gallery_id: int) -> Path:
    repo_root = Path(__file__).resolve().parent.parent
    return repo_root / "src" / "test" / "resources" / "eval" / f"gallery-{gallery_id}.json"


def query(args: argparse.Namespace, sql: str) -> list[list[str | None]]:
    # -A(정렬 없음) -t(헤더 없음) -F 탭 구분. NULL은 NULL_MARKER로 받아 None으로 되돌린다.
    conninfo = f"host={args.host} port={args.port} dbname={args.dbname} user={args.user} sslmode=prefer"
    result = subprocess.run(
        ["psql", conninfo, "-X", "-A", "-t", "-F", "\t", "-P", f"null={NULL_MARKER}", "-c", sql],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql 실패:\n{result.stderr.strip()}")
    return [
        [None if cell == NULL_MARKER else cell for cell in line.split("\t")]
        for line in result.stdout.splitlines() if line
    ]


def none_to_empty(value: str | None) -> str:
    return value if value is not None else ""


if __name__ == "__main__":
    sys.exit(main())
