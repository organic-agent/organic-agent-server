#!/usr/bin/env bash
# 재처리 시간(QA-2) — 장애 주입(fault.injected)으로 죽은 호출마다 "실패 → 다시 처리 끝"을 잰다. 읽기 전용.
#
#   scripts/load/traffic-sqs-redis/recovery.sh dev --label R-3-b --since "2026-10-12 14:00" --until "2026-10-12 14:30"
#
# 임베더: 죽은 호출의 사진들(photoIds)에 벡터가 들어온 시각(photo_analysis.created_at 최대) − 실패 시각.
# categorize: 그 잡의 배정 행이 들어온 시각(concept_assignments.created_at 최대, clock_timestamp) − 실패 시각.
# "다시 처리 끝"이라 시작보다 배치 한 번(임베더 p50 ≈ 25초, categorize ≈ 25초)만큼 길다 — QA-2(시작 ≤ 60초)는 끝 ≤ 60초 + 배치 한 번으로 읽는다.
# 아직 안 끝난 것은 "잃음"으로 센다(조회 시각 기준). 시각은 KST.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }

SINCE=""; UNTIL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift ;;
    --until) UNTIL="$2"; shift ;;
    -h|--help) sed -n 2,10p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$SINCE" ] && [ -n "$UNTIL" ] || { echo "--since · --until 이 필요하다(KST)." >&2; exit 1; }
report_connect
epoch_ms() { python3 -c 'import sys,datetime as d; t=d.datetime.strptime(sys.argv[1],"%Y-%m-%d %H:%M").replace(tzinfo=d.timezone(d.timedelta(hours=9))); print(int(t.timestamp()*1000))' "$1"; }
S=$(epoch_ms "$SINCE"); E=$(epoch_ms "$UNTIL")

FAULTS=$(mktemp -t r3-faults.XXXXXX); trap 'rm -f "$FAULTS"' EXIT
for FN in embedder categorize; do
  aws logs filter-log-events --region "$REGION" --log-group-name "/aws/lambda/$RESOURCE_PREFIX-$FN" \
    --start-time "$S" --end-time "$E" --filter-pattern '"fault.injected"' --output json \
    | python3 -c '
import json, re, sys
for e in json.load(sys.stdin)["events"]:
    m = re.search(r"fault\.injected (\w+) gallery=(\d+) (photoIds|jobId)=([\d,]+|None)", e["message"])
    if m:
        print(e["timestamp"], m.group(1), m.group(2), m.group(4))' >> "$FAULTS"
done

# 실패 하나 = 한 줄 VALUES. 임베더는 사진 id 배열, categorize 는 잡 id.
VALUES=$(python3 - "$FAULTS" <<'EOF'
import sys
rows = []
for line in open(sys.argv[1]):
    ts, kind, gallery, ref = line.split()
    ids = ref if kind == "embedder" else ""
    job = ref if kind == "categorize" and ref != "None" else "NULL"
    rows.append(f"(to_timestamp({int(ts)}/1000.0), '{kind}', {gallery}, '{{{ids}}}'::bigint[], {job})")
print(",\n".join(rows))
EOF
)

report_begin "실패 재처리 시간(QA-2)" "장애 주입 fault.injected · $SINCE ~ $UNTIL KST"
if [ -z "$VALUES" ]; then
  report_section "1. 주입된 실패"; report_note "없음 — fault.sh dev on 으로 켰나?"; report_end; exit 0
fi

report_section "1. 실패마다" "끝 = 그 사진들의 벡터(임베더) · 그 잡의 배정(categorize)이 모두 들어온 시각"
report_sql <<SQL
WITH f(failed_at, kind, gallery_id, photo_ids, job_id) AS (VALUES $VALUES),
r AS (
  SELECT f.*,
    CASE f.kind
      WHEN 'embedder' THEN (SELECT CASE WHEN count(a.photo_id) = cardinality(f.photo_ids) THEN max(a.created_at) END
                            FROM photo_analysis a WHERE a.photo_id = ANY(f.photo_ids) AND a.embedding IS NOT NULL)
      ELSE (SELECT max(c.created_at) FROM concept_assignments c WHERE c.job_id = f.job_id)
    END AS done_at
  FROM f)
SELECT to_char(failed_at, 'HH24:MI:SS') AS 실패, kind AS 실행기, gallery_id AS 갤러리,
       coalesce(job_id::text, cardinality(photo_ids) || '장') AS 대상,
       coalesce(to_char(done_at, 'HH24:MI:SS'), '아직') AS 다시끝,
       CASE WHEN done_at IS NULL THEN '잃음' ELSE round(extract(epoch FROM done_at - failed_at))::text || '초' END AS 걸린시간
FROM r ORDER BY failed_at;
SQL

report_section "2. 요약"
report_sql <<SQL
WITH f(failed_at, kind, gallery_id, photo_ids, job_id) AS (VALUES $VALUES),
r AS (
  SELECT f.kind,
    extract(epoch FROM (CASE f.kind
      WHEN 'embedder' THEN (SELECT CASE WHEN count(a.photo_id) = cardinality(f.photo_ids) THEN max(a.created_at) END
                            FROM photo_analysis a WHERE a.photo_id = ANY(f.photo_ids) AND a.embedding IS NOT NULL)
      ELSE (SELECT max(c.created_at) FROM concept_assignments c WHERE c.job_id = f.job_id) END) - f.failed_at) AS secs
  FROM f)
SELECT kind AS 실행기, count(*) AS 실패, count(secs) AS 다시처리, count(*) - count(secs) AS 잃음,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY secs)) AS p50초,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY secs)) AS p95초, round(max(secs)) AS 최대초
FROM r GROUP BY kind ORDER BY kind;
SQL
report_note "QA-2: 시작 ≤ 60초 ⇔ 끝 ≲ 85초(배치 한 번 ≈ 25초 더함) · 잃음 0"
report_end
