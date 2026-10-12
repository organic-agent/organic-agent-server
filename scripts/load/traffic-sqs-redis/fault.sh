#!/usr/bin/env bash
# 장애 주입 켜기·끄기 — dev 임베더·categorize Lambda 의 FAULT_EXIT_RATE 환경 변수(AI #148·#150 초안 이미지가 읽음).
#
#   scripts/load/traffic-sqs-redis/fault.sh dev on [--rate 0.02]
#   scripts/load/traffic-sqs-redis/fault.sh dev off
#   scripts/load/traffic-sqs-redis/fault.sh dev status
#   scripts/load/traffic-sqs-redis/fault.sh dev on --rate 0.5 --only categorize   # 한 함수만(embedder | categorize)
#
# 켜면 호출의 rate 비율이 받자마자 프로세스를 끝낸다(fault.injected 로그). Terraform 밖에서 바꾸는 값이라 다음 dev apply 가
# 지운다 — 회차가 끝나면 off 로 끈다. dev 전용(운영 함수 이름은 받지 않는다).
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }

ACTION="${1:-status}"; shift || true
RATE="0.02"
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rate) [ $# -ge 2 ] || { echo "--rate 에 값이 필요하다." >&2; exit 1; }; RATE="$2"; shift ;;
    --only) [ $# -ge 2 ] || { echo "--only 에 값이 필요하다." >&2; exit 1; }; ONLY="$2"; shift ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done

case "$ACTION" in on|off|status) ;; *) echo "on · off · status 중 하나: $ACTION" >&2; exit 1 ;; esac
case "$ONLY" in ""|embedder|categorize) ;; *) echo "--only 는 embedder · categorize 중 하나: $ONLY" >&2; exit 1 ;; esac
python3 -c 'import sys; r=float(sys.argv[1]); sys.exit(0 if 0 <= r <= 1 else 1)' "$RATE" 2>/dev/null \
  || { echo "--rate 는 0~1 사이 숫자: $RATE" >&2; exit 1; }

MODULES=(embedder categorize); [ -n "$ONLY" ] && MODULES=("$ONLY")
for MODULE in "${MODULES[@]}"; do
  FN="$RESOURCE_PREFIX-$MODULE"
  CURRENT=$(aws lambda get-function-configuration --region "$REGION" --function-name "$FN" --query 'Environment.Variables' --output json)
  if [ "$ACTION" = "status" ]; then
    echo "$FN FAULT_EXIT_RATE=$(echo "$CURRENT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("FAULT_EXIT_RATE","(없음)"))')"
    continue
  fi
  NEXT=$(echo "$CURRENT" | ACTION="$ACTION" RATE="$RATE" python3 -c '
import json, os, sys
env = json.load(sys.stdin)
if os.environ["ACTION"] == "on":
    env["FAULT_EXIT_RATE"] = os.environ["RATE"]
else:
    env.pop("FAULT_EXIT_RATE", None)
print(json.dumps({"Variables": env}))')
  aws lambda update-function-configuration --region "$REGION" --function-name "$FN" --environment "$NEXT" >/dev/null
  aws lambda wait function-updated --region "$REGION" --function-name "$FN"
  echo "$FN → $ACTION$([ "$ACTION" = on ] && echo " ($RATE)")"
done
