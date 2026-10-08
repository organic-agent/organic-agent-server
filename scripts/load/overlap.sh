#!/usr/bin/env bash
# 운영에서 업로드가 실제로 얼마나 겹쳤나 — "같은 시간 창 안에 업로드를 시작한 큰 갤러리 수". 읽기 전용.
# 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 4장(D5 재검토).
#
#   scripts/load/overlap.sh [local|remote] --label D5 [--window 10min] [--min-photos 500] [--top 20]
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

WINDOW="10min"; MIN_PHOTOS=500; TOP=20
while [ $# -gt 0 ]; do
  case "$1" in
    --window) WINDOW="$2"; shift ;;
    --min-photos) MIN_PHOTOS="$2"; shift ;;
    --top) TOP="$2"; shift ;;
    -h|--help) sed -n 2,5p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
report_connect
report_begin "동시 업로드 겹침 분포 (D5 재검토)" "사진 ≥ ${MIN_PHOTOS}장 갤러리, 시작 ±${WINDOW}"

STARTS="SELECT gallery_id, min(created_at) t0, count(*) photos FROM photos
        GROUP BY gallery_id HAVING count(*) >= $MIN_PHOTOS"

report_section "1. 분포" "겹친 갤러리 수(자기 포함)별 갤러리 수 — 1이면 혼자 올림"
report_sql -c "
  WITH s AS ($STARTS),
       o AS (SELECT a.gallery_id, count(b.gallery_id) n FROM s a
             JOIN s b ON b.t0 BETWEEN a.t0 - interval '$WINDOW' AND a.t0 + interval '$WINDOW' GROUP BY a.gallery_id)
  SELECT n AS \"겹친 수\", count(*) AS 갤러리, round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS \"비율 %\"
  FROM o GROUP BY n ORDER BY n"

report_section "2. 가장 많이 겹친 순간 상위 $TOP"
report_sql -c "
  WITH s AS ($STARTS)
  SELECT a.gallery_id AS 갤러리, to_char(a.t0, 'YYYY-MM-DD HH24:MI') AS \"업로드 시작\", a.photos AS 사진,
         count(b.gallery_id) AS \"겹친 수\", sum(b.photos) AS \"창 안 사진 합\"
  FROM s a JOIN s b ON b.t0 BETWEEN a.t0 - interval '$WINDOW' AND a.t0 + interval '$WINDOW'
  GROUP BY a.gallery_id, a.t0, a.photos ORDER BY count(b.gallery_id) DESC, a.t0 DESC LIMIT $TOP"

report_section "3. 요약"
read -r total maxn < <(report_scalar -F ' ' -c "
  WITH s AS ($STARTS),
       o AS (SELECT a.gallery_id, count(b.gallery_id) n FROM s a
             JOIN s b ON b.t0 BETWEEN a.t0 - interval '$WINDOW' AND a.t0 + interval '$WINDOW' GROUP BY a.gallery_id)
  SELECT count(*), coalesce(max(n), 0) FROM o")
report_verdict info "큰 갤러리 수" "${total}개"
report_verdict info "최대 동시 시작(±$WINDOW)" "${maxn}개 — 설계 목표치 5와 비교"

report_end
