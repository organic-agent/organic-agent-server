#!/usr/bin/env bash
# 앱 연결 풀 표본 요약 — dev 앱 컨테이너 로그의 `event=db.pool`(wes DbPoolSampler, 5초)을 앱 서버·풀별·분 단위로 묶는다(가설 H1: 풀 앞 줄 서기).
# 분석 풀(#286 (a))을 켠 뒤로는 `pool=wes-api-user`·`pool=wes-api-analysis` 두 줄이 남는다. 그 전 로그(pool= 없음)는 `shared`로 센다.
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

# 앱 서버가 여러 대면(#313 ASG 2대) 서버마다 따로 묻는다 — 한 대만 보면 다른 서버의 풀 막힘을 놓친다(10-20, #346).
APP_IDS=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-app" "Name=instance-state-name,Values=running" \
  --query "Reservations[].Instances[].InstanceId" --output text)
[ -n "$APP_IDS" ] || { echo "도는 앱 서버가 없다." >&2; exit 1; }
# SSM 표준 출력은 앞 24,000자만 돌려준다(GetCommandInvocation). 표본(5초 × 풀 둘)을 그대로 받으면 약 28분 넘는 구간에서 뒤가 잘린다 —
# 원격에서 풀·분 단위로 미리 집계해 "분 풀 표본 active최대 waiting최대 waiting>0표본" 한 줄씩(줄당 ≈ 35자) 받는다.
# 옛 로그(pool= 없음, #286 (a) 전)는 shared 로 센다.
AGG='{k=$1" "$2; n[k]++; if($3>a[k])a[k]=$3; if($5>w[k])w[k]=$5; if($5>0)z[k]++} END{for(k in n) print k, n[k], a[k]+0, w[k]+0, z[k]+0}'
REMOTE="docker logs --since $(to_utc "$SINCE") --until $(to_utc "$UNTIL") wes-app 2>&1 | grep 'event=db.pool ' | sed -E 's/event=db.pool active=/event=db.pool pool=shared active=/; s/^[0-9-]+T([0-9]{2}:[0-9]{2}):[0-9]{2}[^ ]* .*pool=([^ ]+) active=([0-9]+) idle=([0-9]+) waiting=([0-9]+) total=([0-9]+).*/\\1 \\2 \\3 \\4 \\5 \\6/' | awk '$AGG' | sort"
OUTPUT_LIMIT=24000
RAW=$(mktemp -t r3-pool.XXXXXX); trap 'rm -f "$RAW"' EXIT
for APP_ID in $APP_IDS; do
  CID=$(aws ssm send-command --region "$REGION" --instance-ids "$APP_ID" --document-name AWS-RunShellScript \
    --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$REMOTE")" \
    --query Command.CommandId --output text)
  while true; do
    sleep 3
    STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$APP_ID" --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in Success) break ;; Failed|Cancelled|TimedOut) echo "앱 로그 조회 실패($APP_ID): $STATUS" >&2; exit 1 ;; esac
  done
  OUT=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$APP_ID" --query StandardOutputContent --output text)
  # 한계에 닿았으면 뒤가 잘린 것이다 — 빠진 표본으로 최대·비율을 내면 조용히 틀린다. 보고서를 만들지 않는다.
  if [ "${#OUT}" -ge $((OUTPUT_LIMIT - 200)) ]; then
    echo "앱 로그 집계($APP_ID)가 SSM 출력 한계(${OUTPUT_LIMIT}자)에 닿아 잘렸을 수 있다 — --since·--until 구간을 나눠 다시 조회할 것." >&2
    exit 1
  fi
  printf '%s\n' "$OUT" | sed "s/^/$APP_ID /" >> "$RAW"
done

DB_NAME="DB 안 씀"
report_begin "앱 연결 풀 표본(event=db.pool)" "$(echo $APP_IDS | tr ' ' ',') · $SINCE ~ $UNTIL KST"
report_section "1. 서버·풀별 분 단위" "active·waiting 의 최대 · waiting > 0 인 표본 비율 (표본 5초) · 풀은 서버마다 따로다 · user=사용자 요청 · analysis=주기 작업·폴더 단계 · shared=나누기 전"
python3 - "$RAW" <<'EOF'
import re, sys, collections
rows = collections.defaultdict(dict)
for line in open(sys.argv[1]):
    m = re.match(r"(\S+) (\d\d:\d\d) (\S+) (\d+) (\d+) (\d+) (\d+)$", line.strip())
    if m:
        rows[f"{m.group(3)} @ {m.group(1)}"][m.group(2)] = tuple(int(x) for x in m.groups()[3:])
if not rows:
    print("    표본 없음 — app.observability.db-pool.enabled 가 켜져 있나?")
    sys.exit()
for pool in sorted(rows):
    print(f"\n    [{pool}]")
    print("    분      표본  active최대  waiting최대  waiting>0")
    print("    ─────  ────  ──────────  ───────────  ─────────")
    total = waited = wmax = 0
    for minute in sorted(rows[pool]):
        n, amax, wm, wpos = rows[pool][minute]
        total += n; waited += wpos; wmax = max(wmax, wm)
        print(f"    {minute}  {n:4d}  {amax:10d}  {wm:11d}  {wpos / n * 100:8.0f}%")
    print(f"    전체 waiting 최대 {wmax} · waiting>0 표본 {waited}/{total}")
EOF
report_end
