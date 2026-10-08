#!/usr/bin/env bash
# 부하 측정 타임라인 — 갤러리마다 업로드 시작(T0) → AI 폴더 완료(T_done) 단계별 경과와 판정. 읽기 전용.
# 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 1장(P2).
#
#   scripts/load/timeline.sh [local|remote] --label R1-1 --ids 101,102 [--limit 7min] [--title "..."]
#   scripts/load/timeline.sh [local|remote] --label R1-0 --recent 10           # 최근 큰 갤러리(사진 ≥ 500) 10개
#
#   --ids       대상 갤러리 id (쉼표)
#   --recent N  ids 대신 업로드 시작이 최근인 큰 갤러리 N개
#   --limit     판정 기준 완료(T_done − T0) 상한, PostgreSQL interval (예: 7min, 17min). 없으면 판정 생략
#   --events    잡 이벤트(재전송·떼어냄·폴백 등) 종류별 횟수·시각도 본다
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

IDS=""; RECENT=""; LIMIT=""; EVENTS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ids) IDS="$2"; shift ;;
    --recent) RECENT="$2"; shift ;;
    --limit) LIMIT="$2"; shift ;;
    --events) EVENTS="true" ;;
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$IDS" ] || [ -n "$RECENT" ] || { echo "--ids 또는 --recent 가 필요하다 (--help)" >&2; exit 1; }
report_connect

if [ -n "$RECENT" ]; then
  IDS=$(report_scalar -c "
    SELECT string_agg(gallery_id::text, ',' ORDER BY gallery_id) FROM (
      SELECT gallery_id FROM photos WHERE deleted_at IS NULL
      GROUP BY gallery_id HAVING count(*) >= 500 ORDER BY min(created_at) DESC LIMIT $RECENT) s")
  [ -n "$IDS" ] || { echo "사진 500장 이상 갤러리가 없다." >&2; exit 1; }
fi
SQL_DIR="$(cd "$(dirname "$0")" && pwd)"

report_begin "업로드 → AI 폴더 타임라인 (T0 기준 경과)" "갤러리 $IDS"

report_section "1. 갤러리" "제목 · 상태 · 업로드 시작 시각"
report_sql -c "
  SELECT g.id AS 갤러리, left(g.title, 30) AS 제목, g.status AS 상태,
         to_char(min(p.created_at), 'MM-DD HH24:MI:SS') AS \"T0 업로드 시작\"
  FROM galleries g LEFT JOIN photos p ON p.gallery_id = g.id AND p.deleted_at IS NULL
  WHERE g.id = ANY('{$IDS}'::bigint[]) GROUP BY g.id ORDER BY g.id"

report_section "2. 단계별 경과 (분:초)" "업로드 시작(T0)부터 — Mbps = 원본 바이트 합 × 8 / 업로드 시간 · 업로드·임베딩·점수 = 마지막 사진 · 잡생성 · cat보냄/cat끝 = categorize 보냄/결과 도착 · 완료 = DONE(폴더 만들고 잡 닫힘) · 업로드 뒤 = 마지막 업로드 → 완료"
report_sql -v ids="{$IDS}" < "$SQL_DIR/timeline.sql"

# 갤러리마다 마지막 잡의 T0 · 마지막 업로드 · 완료 시각 (판정·이벤트가 같이 쓴다)
BASE="
  WITH p AS (SELECT gallery_id, min(created_at) t0, max(uploaded_at) t_up FROM photos
             WHERE gallery_id = ANY('{$IDS}'::bigint[]) AND deleted_at IS NULL GROUP BY gallery_id),
       j AS (SELECT DISTINCT ON (gallery_id) id job_id, gallery_id, status, finished_at FROM analysis_jobs
             WHERE gallery_id = ANY('{$IDS}'::bigint[]) ORDER BY gallery_id, id DESC),
       b AS (SELECT p.*, j.job_id,
                    coalesce((SELECT min(e.created_at) FROM analysis_job_events e WHERE e.job_id = j.job_id AND e.type = 'DONE'),
                             CASE WHEN j.status = 'DONE' THEN j.finished_at END) t_done
             FROM p LEFT JOIN j USING (gallery_id))"
mmss() { echo "floor(extract(epoch FROM $1) / 60)::int || ':' || lpad((floor(extract(epoch FROM $1))::int % 60)::text, 2, '0')"; }

if [ -n "$EVENTS" ]; then
  report_section "3. 잡 이벤트" "마지막 잡의 이벤트 종류별 횟수와 처음 · 마지막 시각 (T0 기준 분:초)"
  report_sql -c "$BASE
    SELECT b.gallery_id AS 갤러리, e.type AS 이벤트, count(*) AS 횟수,
           $(mmss "min(e.created_at) - b.t0") AS 처음, $(mmss "max(e.created_at) - b.t0") AS 마지막
    FROM b JOIN analysis_job_events e ON e.job_id = b.job_id
    GROUP BY b.gallery_id, b.t0, e.type ORDER BY b.gallery_id, min(e.created_at)"
fi

if [ -n "$LIMIT" ]; then
  report_section "$([ -n "$EVENTS" ] && echo 4 || echo 3). 판정" "완료(T_done − T0) ≤ $LIMIT · 업로드 뒤는 참고"
  while IFS='|' read -r gid total after ok stale; do
    if [ "$stale" = "t" ]; then report_verdict info "갤러리 $gid" "제외 — 업로드 하루 뒤 재분석된 잡"
    elif [ -z "$total" ]; then report_verdict info "갤러리 $gid" "DONE 없음(진행 중 또는 실패)"
    elif [ "$ok" = "t" ]; then report_verdict pass "갤러리 $gid ≤ $LIMIT" "$total  (업로드 뒤 $after)"
    else report_verdict fail "갤러리 $gid ≤ $LIMIT" "$total  (업로드 뒤 $after)"; fi
  done < <(report_scalar -c "$BASE
    SELECT gallery_id, coalesce($(mmss "t_done - t0"), ''), coalesce($(mmss "t_done - t_up"), ''),
           (t_done - t0) <= interval '$LIMIT', coalesce(t_done - t0 >= interval '1 day', false)
    FROM b ORDER BY gallery_id")
fi

report_end
