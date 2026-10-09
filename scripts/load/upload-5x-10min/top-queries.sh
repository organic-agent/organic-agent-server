#!/usr/bin/env bash
# 무거운 쿼리 순위 — pg_stat_statements 로 측정 회차 동안 DB 시간·버퍼·임시 파일을 가장 많이 쓴 쿼리를 본다.
# 계획: docs/plans/2026-10-08/upload-5x-10min/03-options.md §4 "어느 쿼리가 메모리·시간을 쓰나" (D-3 근거). PI 는 조직 SCP로 조회 불가.
#
#   scripts/load/upload-5x-10min/top-queries.sh dev --setup                      # 한 번: 확장 만들기 (쓰기)
#   scripts/load/upload-5x-10min/top-queries.sh dev --reset                      # 회차 직전: 통계 비우기 (쓰기)
#   scripts/load/upload-5x-10min/top-queries.sh dev --label B-2-3-q [--top 15]   # 회차 뒤: 순위 (읽기 전용)
#
# 순위 넷: 실행 시간 합 · 공유 버퍼 읽기(디스크) · 임시 파일 쓰기(work_mem 넘침) · 호출 수.
# 메모리를 직접 재는 값은 없다 — 버퍼 읽기(캐시에 못 담음)와 임시 파일(work_mem 넘침)이 메모리 압박의 대리 지표다.
# 출력: 화면 + docs/experiments/load-runs/{label}-{시각}.txt
set -euo pipefail
source "$(dirname "$0")/../lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

MODE="report"; TOP=15
while [ $# -gt 0 ]; do
  case "$1" in
    --setup) MODE="setup" ;;
    --reset) MODE="reset" ;;
    --top) TOP="$2"; shift ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "remote" ] && [ "$MODE" != "report" ] && { echo "운영(remote)에는 --setup·--reset 을 쓰지 않는다." >&2; exit 1; }
report_connect

# 쓰기 연결 — report_sql 은 읽기 전용 세션이라 따로 연다.
write_sql() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" -e PGCLIENTENCODING=UTF8 \
    pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}

case "$MODE" in
  setup)
    preload=$(report_scalar -c "SHOW shared_preload_libraries")
    case "$preload" in *pg_stat_statements*) ;; *) echo "shared_preload_libraries 에 pg_stat_statements 가 없다: $preload (파라미터 그룹 변경·재부팅 필요)" >&2; exit 1 ;; esac
    write_sql -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"
    echo "확장 준비됨: $(report_scalar -c "SELECT extversion FROM pg_extension WHERE extname = 'pg_stat_statements'")"
    exit 0 ;;
  reset)
    write_sql -c "SELECT pg_stat_statements_reset()" >/dev/null
    echo "통계 비움: $(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST') — 회차를 시작한 뒤 끝나면 순위를 볼 것"
    exit 0 ;;
esac

report_begin "무거운 쿼리 순위 (pg_stat_statements)" "상위 ${TOP}개 · 이 DB의 쿼리 (사용자 열로 앱·Lambda 구분)"

report_section "0. 조건" "통계 시작 시각과 메모리 파라미터 — 회차마다 같은지 본다"
report_sql -c "
  SELECT to_char(i.stats_reset AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI:SS') AS 통계_시작,
         to_char(now() - i.stats_reset, 'HH24:MI:SS') AS 경과,
         current_setting('shared_buffers') AS shared_buffers,
         current_setting('work_mem') AS work_mem,
         current_setting('effective_cache_size') AS effective_cache_size,
         current_setting('max_connections') AS max_conn,
         (SELECT count(*) FROM pg_stat_statements) AS 쿼리_종류
  FROM pg_stat_statements_info i"

# 공통 열: 사용자, 합·비중(이 DB 전체 실행 시간 대비 %), 호출, 평균·최대, 버퍼, 임시 파일, 쿼리 앞 110자(공백 하나로).
COLS="
  r.rolname AS 사용자,
  round((s.total_exec_time / 1000)::numeric, 1) AS 합_초,
  round((100 * s.total_exec_time / nullif((SELECT sum(total_exec_time) FROM pg_stat_statements), 0))::numeric, 1) AS 비중,
  s.calls AS 호출,
  round(s.mean_exec_time::numeric, 1) AS 평균_ms,
  round(s.max_exec_time::numeric) AS 최대_ms,
  s.shared_blks_read * 8 / 1024 AS 디스크읽기_mb,
  s.shared_blks_hit * 8 / 1024 AS 캐시읽기_mb,
  s.temp_blks_written * 8 / 1024 AS 임시쓰기_mb,
  left(regexp_replace(s.query, '\s+', ' ', 'g'), 110) AS 쿼리"
FROM="FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid
  WHERE s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    AND s.query NOT ILIKE '%pg_stat_statements%'"

report_section "1. 실행 시간 합" "DB 시간을 가장 많이 쓴 쿼리 — 비중은 전체 실행 시간 대비 %"
report_sql -c "SELECT $COLS $FROM ORDER BY s.total_exec_time DESC LIMIT $TOP"

report_section "2. 디스크 읽기" "공유 버퍼에 못 담아 디스크에서 읽은 양 — shared_buffers 가 작을수록 커진다"
report_sql -c "SELECT $COLS $FROM AND s.shared_blks_read > 0 ORDER BY s.shared_blks_read DESC LIMIT $TOP"

report_section "3. 임시 파일 쓰기" "정렬·해시가 work_mem 을 넘쳐 디스크로 쏟은 양 — 0 이면 표가 비어 있다"
report_sql -c "SELECT $COLS $FROM AND s.temp_blks_written > 0 ORDER BY s.temp_blks_written DESC LIMIT $TOP"

report_section "4. 호출 수" "자주 불리는 쿼리 — 평균이 작아도 합이 크면 스윕·폴링을 의심"
report_sql -c "SELECT $COLS $FROM ORDER BY s.calls DESC LIMIT $TOP"

report_section "5. 합계"
report_sql -c "
  SELECT round((sum(s.total_exec_time) / 1000)::numeric, 1) AS 실행_합_초, sum(s.calls) AS 호출_합,
         (sum(s.shared_blks_read) * 8 / 1024)::bigint AS 디스크읽기_mb, (sum(s.shared_blks_hit) * 8 / 1024)::bigint AS 캐시읽기_mb,
         (sum(s.temp_blks_written) * 8 / 1024)::bigint AS 임시쓰기_mb,
         round((100.0 * sum(s.shared_blks_hit) / nullif(sum(s.shared_blks_hit + s.shared_blks_read), 0))::numeric, 1) AS 캐시적중_pct
  $FROM"
report_end
