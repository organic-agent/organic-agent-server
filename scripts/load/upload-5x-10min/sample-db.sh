#!/usr/bin/env bash
# DB 연결 샘플 — 측정 회차와 같이 띄워 두면 N초마다 연결 수를 사용자·애플리케이션·상태별로 센다. 가드레일 G-1(연결 ≤ 63) 판정. 읽기 전용.
# 계획: docs/plans/2026-10-08/upload-5x-10min/01-drivers.md G-1 · 02-baseline.md P3.
#
#   scripts/load/upload-5x-10min/sample-db.sh dev --label B-2-1-db [--interval 5] [--limit 63]     # Ctrl+C 로 끝내면 요약과 판정
#
# 세는 것: pg_stat_activity 의 client backend (모든 DB). RDS max_connections 가 세는 것과 같다.
# 샘플러 자신도 샘플마다 연결 1개를 잠깐 쓴다 — 결과에 포함되어 있다.
# 출력: 화면 + docs/experiments/load-runs/{label}-{시각}.txt, 원자료 CSV 는 같은 이름 .csv
set -euo pipefail
source "$(dirname "$0")/../lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

INTERVAL=5; LIMIT=63
while [ $# -gt 0 ]; do
  case "$1" in
    --interval) INTERVAL="$2"; shift ;;
    --limit) LIMIT="$2"; shift ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
report_connect
report_begin "DB 연결 샘플 (가드레일 G-1)" "${INTERVAL}초 간격 · 선 ${LIMIT}"
CSV="${REPORT_FILE%.txt}.csv"
echo "sampled_at,usename,application_name,state,connections" > "$CSV"
report_note "원자료: $CSV · Ctrl+C 로 끝내면 요약"

MAX_TOTAL=0; MAX_AT=""; SAMPLES=0
summary() {
  trap - INT TERM
  report_section "요약" "샘플 ${SAMPLES}개"
  report_note "최대 ${MAX_TOTAL} (${MAX_AT})"
  # 최대였던 순간의 구성
  awk -F, -v at="$MAX_AT" 'NR > 1 && $1 == at { printf "    %-20s %-28s %-22s %s\n", $2, $3, $4, $5 }' "$CSV"
  report_section "사용자별 최대"
  awk -F, 'NR > 1 { k = $1 FS $2; s[k] += $5 } END { for (k in s) { split(k, a, FS); if (s[k] > m[a[2]]) m[a[2]] = s[k] } for (u in m) printf "    %-20s %d\n", u, m[u] }' "$CSV" | sort -k2 -nr
  report_section "판정"
  if [ "$MAX_TOTAL" -le "$LIMIT" ]; then report_verdict pass "G-1 연결 ≤ $LIMIT" "$MAX_TOTAL"
  else report_verdict fail "G-1 연결 ≤ $LIMIT" "$MAX_TOTAL"; fi
  report_end
  exit 0
}
trap summary INT TERM

report_section "샘플" "시각 · 합계 · 상위 사용자"
while true; do
  rows=$(report_scalar -F ',' -c "
    SELECT to_char(now(), 'HH24:MI:SS'), coalesce(usename, '-'), coalesce(nullif(application_name, ''), '-'),
           coalesce(state, '-'), count(*)
    FROM pg_stat_activity WHERE backend_type = 'client backend'
    GROUP BY 2, 3, 4" || true)
  if [ -n "$rows" ]; then
    echo "$rows" >> "$CSV"
    at=$(head -1 <<< "$rows" | cut -d, -f1)
    total=$(awk -F, '{ s += $5 } END { print s + 0 }' <<< "$rows")
    top=$(awk -F, '{ u[$2] += $5 } END { for (k in u) printf "%s=%d ", k, u[k] }' <<< "$rows")
    SAMPLES=$((SAMPLES + 1))
    if [ "$total" -gt "$MAX_TOTAL" ]; then MAX_TOTAL=$total; MAX_AT=$at; fi
    mark=""; [ "$total" -gt "$LIMIT" ] && mark="  ${C_RD}▲ 선 넘음${C_0}"
    echo "    $at  합계 ${C_B}$total${C_0}  ${C_DIM}$top${C_0}$mark"
  fi
  sleep "$INTERVAL"
done
