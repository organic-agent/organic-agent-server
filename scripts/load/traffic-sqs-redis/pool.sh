#!/usr/bin/env bash
# 앱 연결 풀 표본 요약 — dev 앱 컨테이너 로그의 `event=db.pool`(wes DbPoolSampler, 5초)을 분 단위로 묶는다(가설 H1: 풀 앞 줄 서기).
#
#   scripts/load/traffic-sqs-redis/pool.sh dev --label R-3-a --since "2026-10-12 14:00" --until "2026-10-12 14:15"
#
# 시각은 KST. 표본 로그는 SSM /wes/dev/app.observability.db-pool.enabled=true 로 켠 앱에서만 나온다. 읽기 전용.
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
    -h|--help) sed -n 2,7p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$SINCE" ] && [ -n "$UNTIL" ] || { echo "--since · --until 이 필요하다(KST)." >&2; exit 1; }
to_utc() { python3 -c 'import sys,datetime as d; t=d.datetime.strptime(sys.argv[1],"%Y-%m-%d %H:%M").replace(tzinfo=d.timezone(d.timedelta(hours=9))); print(t.astimezone(d.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }

APP_ID=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-app" "Name=instance-state-name,Values=running" \
  --query "Reservations[0].Instances[0].InstanceId" --output text)
# SSM 표준 출력은 약 24,000자에서 잘린다 — 원격에서 "시각 active idle waiting total" 으로 줄여(줄당 ≈ 25자) 받는다.
REMOTE="docker logs --since $(to_utc "$SINCE") --until $(to_utc "$UNTIL") wes-app 2>&1 | grep 'event=db.pool' | sed -E 's/^[0-9-]+T([0-9:]+)[^ ]* .*active=([0-9]+) idle=([0-9]+) waiting=([0-9]+) total=([0-9]+).*/\\1 \\2 \\3 \\4 \\5/'"
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$APP_ID" --document-name AWS-RunShellScript \
  --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$REMOTE")" \
  --query Command.CommandId --output text)
RAW=$(mktemp -t r3-pool.XXXXXX); trap 'rm -f "$RAW"' EXIT
while true; do
  sleep 3
  STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$APP_ID" --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Success) break ;; Failed|Cancelled|TimedOut) echo "앱 로그 조회 실패: $STATUS" >&2; exit 1 ;; esac
done
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$APP_ID" --query StandardOutputContent --output text > "$RAW"

DB_NAME="DB 안 씀"
report_begin "앱 연결 풀 표본(event=db.pool)" "$APP_ID · $SINCE ~ $UNTIL KST"
report_section "1. 분 단위" "active·waiting 의 최대 · waiting > 0 인 표본 비율 (표본 5초)"
python3 - "$RAW" <<'EOF'
import re, sys, collections
rows = collections.defaultdict(list)
for line in open(sys.argv[1]):
    m = re.match(r"(\d\d:\d\d):\d\d\S* (\d+) (\d+) (\d+) (\d+)$", line.strip())
    if m:
        rows[m.group(1)].append(tuple(int(x) for x in m.groups()[1:]))
if not rows:
    print("    표본 없음 — app.observability.db-pool.enabled 가 켜져 있나?")
    sys.exit()
print("    분      표본  active최대  waiting최대  waiting>0")
print("    ─────  ────  ──────────  ───────────  ─────────")
allw = []
for minute in sorted(rows):
    s = rows[minute]
    w = [x[2] for x in s]; allw += w
    print(f"    {minute}  {len(s):4d}  {max(x[0] for x in s):10d}  {max(w):11d}  {sum(1 for v in w if v > 0) / len(w) * 100:8.0f}%")
print(f"\n    전체 waiting 최대 {max(allw)} · waiting>0 표본 {sum(1 for v in allw if v > 0)}/{len(allw)}")
EOF
report_end
