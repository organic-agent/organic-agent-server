#!/usr/bin/env bash
# R-3 사용자 트래픽 실행 — dev 부하 발생기 EC2(Name=wes-dev-loadgen, infra #97)에서 k6 를 돌리고, 요약을 받아 단계별·동작별로 판정한다.
#
#   scripts/load/traffic-sqs-redis/run-k6.sh dev --label R-3-a [--profile full|smoke|steady] [--intensity 2] [--keep-on] [--csv]
#   --intensity N  한 사람의 동작 횟수 배수(비율은 운영 로그 그대로, users.js RATES × N). 기본 2 = 분당 ≈ 24.6
#   --csv          요청마다 시각·상태를 requests.csv.gz 로도 받는다(초 단위 실패 타임라인 — availability-2-servers/gap.sh)
#   scripts/load/traffic-sqs-redis/run-k6.sh dev --label R-3-a --start-only     # 발생기만 켜 둔다(재생과 같은 순간에 시작하려고)
#   scripts/load/traffic-sqs-redis/run-k6.sh dev --label R-3-a --report-only    # 이미 받은 요약으로 판정만 다시
#
# 입력은 prepare.sh 가 올린 load-results/<label>/inputs/{targets.json,users.js}. 결과는 load-results/<label>/{summary.json,k6.log}
# 그리고 docs/experiments/load-runs/<label>-k6-summary.json. 끝나면 발생기를 끈다(--keep-on 이면 둔다). dev 쓰기 없음(API 쓰기는 k6 가 한다).
# 판정 기준은 01-drivers QA-1: 200명 단계 p95 ≤ 500ms · 오류율 < 1%.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

PROFILE="full"; INTENSITY="2"; KEEP_ON="false"; MODE="run"; CSV="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift ;;
    --intensity) INTENSITY="$2"; shift ;;
    --keep-on) KEEP_ON="true" ;;
    --csv) CSV="true" ;;
    --start-only) MODE="start" ;;
    --report-only) MODE="report" ;;
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다." >&2; exit 1; }

API="https://dev.api.easyselect.kr"
BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
S3_PREFIX="s3://$BUCKET/load-results/$REPORT_LABEL"
RUNS_DIR="${LOAD_RUNS_DIR:-docs/experiments/load-runs}"
LOCAL_SUMMARY="$RUNS_DIR/$REPORT_LABEL-k6-summary.json"
CSV_OUT=""; CSV_UPLOAD=""
if [ "$CSV" = "true" ]; then
  CSV_OUT="--out csv=requests.csv.gz"
  CSV_UPLOAD="aws s3 cp --only-show-errors --region $REGION requests.csv.gz $S3_PREFIX/requests.csv.gz"
fi
mkdir -p "$RUNS_DIR"

instance_id() {
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-loadgen" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query "Reservations[0].Instances[0].InstanceId" --output text
}

wait_online() {
  local id="$1" i
  for i in $(seq 1 60); do
    [ "$(aws ssm describe-instance-information --region "$REGION" --filters "Key=InstanceIds,Values=$id" \
      --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null)" = "Online" ] && return 0
    sleep 5
  done
  echo "발생기 $id 가 SSM 에 붙지 않는다." >&2; exit 1
}

start_loadgen() {
  local id; id=$(instance_id)
  [ "$id" != "None" ] || { echo "발생기($RESOURCE_PREFIX-loadgen)가 없다 — infra environments/dev loadgen.tf." >&2; exit 1; }
  # 직전 회차가 끄는 중이면 start 가 IncorrectInstanceState 로 거절된다 — 다 꺼질 때까지 기다린다.
  [ "$(aws ec2 describe-instances --region "$REGION" --instance-ids "$id" --query 'Reservations[0].Instances[0].State.Name' --output text)" = stopping ] \
    && aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$id"
  aws ec2 start-instances --region "$REGION" --instance-ids "$id" >/dev/null
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$id"
  wait_online "$id"
  echo "$id"
}

stop_loadgen() {
  local id; id=$(instance_id)
  [ "$id" = "None" ] || aws ec2 stop-instances --region "$REGION" --instance-ids "$id" >/dev/null
}

run_remote() {
  local id="$1" cid status
  local script="set -e; ulimit -n 65535; rm -rf /opt/r3 && mkdir -p /opt/r3 && cd /opt/r3
aws s3 cp --only-show-errors --region $REGION $S3_PREFIX/inputs/targets.json targets.json
aws s3 cp --only-show-errors --region $REGION $S3_PREFIX/inputs/users.js users.js
k6 run --quiet $CSV_OUT -e API=$API -e TARGETS=targets.json -e PROFILE=$PROFILE -e INTENSITY=$INTENSITY -e SUMMARY=summary.json users.js > k6.log 2>&1 || echo \"k6 exit \$?\" >> k6.log
aws s3 cp --only-show-errors --region $REGION k6.log $S3_PREFIX/k6.log
aws s3 cp --only-show-errors --region $REGION summary.json $S3_PREFIX/summary.json
$CSV_UPLOAD"
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$id" --document-name AWS-RunShellScript \
    --timeout-seconds 600 --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]],"executionTimeout":["3600"]}))' "$script")" \
    --query Command.CommandId --output text)
  echo "k6 시작 $(TZ=Asia/Seoul date '+%H:%M:%S') · profile=$PROFILE · 강도 ${INTENSITY}× · 명령 $cid" >&2
  while true; do
    sleep 15
    status=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$id" --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Success|Failed|Cancelled|TimedOut) break ;; esac
  done
  echo "k6 끝 $(TZ=Asia/Seoul date '+%H:%M:%S') · $status" >&2
}

if [ "$MODE" = "start" ]; then
  echo "발생기 켬: $(start_loadgen)"; exit 0
fi

if [ "$MODE" = "run" ]; then
  # 켜다 실패하거나 중간에 끊겨도 발생기가 켜진 채 남지 않게 한다.
  [ "$KEEP_ON" = "true" ] || trap 'stop_loadgen' EXIT
  ID=$(start_loadgen)
  run_remote "$ID"
  aws s3 cp --only-show-errors --region "$REGION" "$S3_PREFIX/summary.json" "$LOCAL_SUMMARY"
  aws s3 cp --only-show-errors --region "$REGION" "$S3_PREFIX/k6.log" "$RUNS_DIR/$REPORT_LABEL-k6.log"
  [ "$CSV" = "true" ] && aws s3 cp --only-show-errors --region "$REGION" "$S3_PREFIX/requests.csv.gz" "$RUNS_DIR/$REPORT_LABEL-requests.csv.gz"
  if [ "$KEEP_ON" != "true" ]; then stop_loadgen; trap - EXIT; fi
fi

[ -f "$LOCAL_SUMMARY" ] || { echo "요약이 없다: $LOCAL_SUMMARY" >&2; exit 1; }

DB_NAME="DB 안 씀"
report_begin "사용자 트래픽(k6) 단계별·동작별 응답" "가상 사용자 → $API · profile $PROFILE · 강도 ${INTENSITY}×"

TABLES=$(mktemp -d -t r3-k6.XXXXXX); trap 'rm -rf "$TABLES"' EXIT
python3 - "$LOCAL_SUMMARY" "$TABLES" > "$TABLES/verdict.env" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1]))["metrics"]

def trend(key):
    v = m.get(key, {}).get("values", {})
    return v.get("p(50)"), v.get("p(95)"), v.get("p(99)"), v.get("max")

def rate(key):
    v = m.get(key, {}).get("values", {})
    return v.get("rate"), (v.get("passes", 0) + v.get("fails", 0))

def ms(x):
    return "—" if x is None else f"{x:,.0f}"

stages = sorted({k.split("stage:")[1].rstrip("}") for k in m if "{stage:" in k and k.startswith("http_req_duration")},
                key=lambda s: (not s.isdigit(), int(s) if s.isdigit() else 0))
rows = [("단계", "요청", "p50", "p95", "p99", "최대", "오류율")]
verdict = {}
for s in stages:
    p50, p95, p99, mx = trend(f"http_req_duration{{stage:{s}}}")
    r, n = rate(f"http_req_failed{{stage:{s}}}")
    rows.append((f"{s}명" if s.isdigit() else s, f"{n:,}", ms(p50), ms(p95), ms(p99), ms(mx), "—" if r is None else f"{r*100:.2f}%"))
    verdict[s] = (p95, r)
actions = sorted(k.split("action:")[1].rstrip("}") for k in m if k.startswith("http_req_duration{action:"))
rows2 = [("동작", "p50", "p95", "p99", "최대")]
for a in actions:
    p50, p95, p99, mx = trend(f"http_req_duration{{action:{a}}}")
    rows2.append((a, ms(p50), ms(p95), ms(p99), ms(mx)))

def table(rows):
    widths = [max(len(str(r[i])) for r in rows) for i in range(len(rows[0]))]
    out = []
    for j, r in enumerate(rows):
        out.append("    " + "  ".join(str(c).rjust(widths[i]) for i, c in enumerate(r)))
        if j == 0:
            out.append("    " + "  ".join("─" * w for w in widths))
    return "\n".join(out)

open(f"{sys.argv[2]}/stages.txt", "w").write(table(rows) + "\n")
open(f"{sys.argv[2]}/actions.txt", "w").write(table(rows2) + "\n")
p95, r = verdict.get("200", (None, None))
print(f"P95_200={'' if p95 is None else round(p95)}")
print(f"ERR_200={'' if r is None else f'{r*100:.2f}'}")
def broken(s):
    p95, r = verdict[s]
    return (p95 or 0) > 500 or (r or 0) >= 0.01

first = next((s for s in stages if s.isdigit() and broken(s)), "")
print(f"FIRST_BREAK={first}")
total = m.get("http_reqs", {}).get("values", {}).get("count", 0)
print(f"TOTAL_REQS={int(total)}")
EOF
# shellcheck disable=SC1091
source "$TABLES/verdict.env"

report_section "1. 단계별 (목표 사용자 수)" "응답 시간 ms · 오류율 = 2xx 아님(담기·빼기의 404·409 는 정상으로 셈)"
cat "$TABLES/stages.txt"
report_section "2. 동작별 (전 구간)" "open = 갤러리·멤버·폴더·셀렉·추천·협업 세션(요약 없음, 웹과 같음) · 그 뒤 운영 빈도(02 §15): list 200장 한 페이지 · watch 셀렉 상태 · retouch 보정 회차"
cat "$TABLES/actions.txt"

report_section "3. 판정 (01 QA-1)"
report_verdict info "전체 요청" "${TOTAL_REQS}건"
if [ -z "$P95_200" ]; then
  report_verdict info "200명 단계" "없음(profile $PROFILE)"
else
  awk "BEGIN{exit !($P95_200 <= 500)}" && v=pass || v=fail; report_verdict "$v" "200명 p95 ≤ 500ms" "${P95_200}ms"
  awk "BEGIN{exit !($ERR_200 < 1)}" && v=pass || v=fail; report_verdict "$v" "200명 오류율 < 1%" "${ERR_200}%"
fi
report_verdict info "처음 무너진 단계(p95 > 500ms 또는 오류율 ≥ 1%)" "${FIRST_BREAK:-없음}${FIRST_BREAK:+명}"
report_note "원본: $LOCAL_SUMMARY · $RUNS_DIR/$REPORT_LABEL-k6.log"
report_end
