#!/usr/bin/env bash
# 부하 재생 씨앗 — 운영 원본 갤러리의 사진을 dev 버킷 load-seed/<id>/ 로 서버 쪽 복사하고 도착 시각표를 만든다. 한 번만 돌린다.
# 대상: dev (쓰는 곳) · --from 원본 환경(보통 prod, 읽기만)
# 바꾸는 것: dev 버킷 load-seed/<id>/ · 로컬 docs/experiments/load-seed/ 시각표
# 계획: docs/plans/2026-10-08/upload-5x-10min/02-baseline.md 2.1·2.2(P5).
#
#   scripts/load/upload/seed-dev.sh --from prod dev --source 48   # 준비: 다른 터미널에서 scripts/dev-tools/db-tunnel.sh prod (운영 15432, 읽기만)
#
#   --from ENV  원본을 읽을 환경(보통 prod, 읽기만). 그 뒤의 환경 인자(dev)가 씨앗을 쓸 곳이다 — 두 환경이 명령 줄에 다 보이게 한다.
#
# 운영은 읽기만 한다: DB는 읽기 전용 세션, 버킷은 HeadObject·복사 원본. 쓰기는 dev 버킷 씨앗 폴더뿐.
# 시각표: docs/experiments/load-seed/<id>-timetable.json (gitignore) — 사진별 issue_offset(created_at−T0)·upload_offset(uploaded_at−T0)
set -euo pipefail
source "$(dirname "$0")/../lib/report.sh"
cd "$(dirname "$0")/../../.."
SOURCE=""; FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift ;;
    --source) SOURCE="$2"; shift ;;
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) env_take "$1" || { echo "알 수 없는 인자: $1" >&2; exit 1; } ;;
  esac
  shift
done
[ -n "$SOURCE" ] || { echo "--source 원본 갤러리 id 가 필요하다" >&2; exit 1; }
[ -n "$FROM" ] || { echo "--from 원본 환경(보통 prod)이 필요하다" >&2; exit 1; }
[ "$ENV_TARGET" = "dev" ] || { echo "씨앗은 dev 에만 쓴다 — 쓰는 환경 인자 dev 가 필요하다." >&2; exit 1; }
DST_SSM_PREFIX="$SSM_PREFIX"
# 원본 환경 DB 는 읽기 전용 세션(report_scalar)으로만 읽는다.
env_take "$FROM" || { echo "--from 은 local | dev | prod 중 하나" >&2; exit 1; }
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
echo "$ENV_NAME 갤러리 $SOURCE 사진 $(wc -l < "$CSV" | tr -d ' ')장 → $CSV"

source scripts/load/lib/venv.sh
"$REPLAY_PY" scripts/load/upload/replay.py seed --src-ssm-prefix "$SSM_PREFIX" --ssm-prefix "$DST_SSM_PREFIX" --source "$SOURCE" --csv "$CSV" --out "$OUT_DIR/$SOURCE-timetable.json"
