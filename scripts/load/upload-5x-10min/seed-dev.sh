#!/usr/bin/env bash
# 부하 재생 씨앗 — 운영 원본 갤러리의 사진을 dev 버킷 load-seed/<id>/ 로 서버 쪽 복사하고 도착 시각표를 만든다. 한 번만 돌린다.
# 계획: docs/plans/2026-10-08/upload-5x-10min/02-baseline.md 2.1·2.2(P5).
#
#   scripts/load/upload-5x-10min/seed-dev.sh --source 48            # 준비: 다른 터미널에서 scripts/db-tunnel.sh (운영 15432, 읽기만)
#
# 운영은 읽기만 한다: DB는 읽기 전용 세션, 버킷은 HeadObject·복사 원본. 쓰기는 dev 버킷 씨앗 폴더뿐.
# 시각표: docs/experiments/load-seed/<id>-timetable.json (gitignore) — 사진별 issue_offset(created_at−T0)·upload_offset(uploaded_at−T0)
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
SOURCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift ;;
    -h|--help) sed -n 2,8p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$SOURCE" ] || { echo "--source 원본 갤러리 id 가 필요하다" >&2; exit 1; }
REPORT_TARGET="remote"; report_target_vars
report_connect

OUT_DIR="docs/experiments/load-seed"; mkdir -p "$OUT_DIR"
CSV="$OUT_DIR/$SOURCE-photos.csv"
report_scalar --csv -c "
  WITH t AS (SELECT min(created_at) t0 FROM photos WHERE gallery_id = $SOURCE AND deleted_at IS NULL)
  SELECT p.storage_key, p.original_file_name, p.content_type, p.display_order,
         round(extract(epoch FROM p.created_at - t.t0)::numeric, 3),
         round(extract(epoch FROM p.uploaded_at - t.t0)::numeric, 3)
  FROM photos p, t
  WHERE p.gallery_id = $SOURCE AND p.deleted_at IS NULL AND p.status = 'UPLOADED' AND p.uploaded_at IS NOT NULL
  ORDER BY p.uploaded_at, p.id" > "$CSV"
echo "운영 갤러리 $SOURCE 사진 $(wc -l < "$CSV" | tr -d ' ')장 → $CSV"

source scripts/load/upload-5x-10min/venv.sh
"$REPLAY_PY" scripts/load/upload-5x-10min/replay.py seed --source "$SOURCE" --csv "$CSV" --out "$OUT_DIR/$SOURCE-timetable.json"
