#!/usr/bin/env bash
# 가용성 회차의 자극 — k6 일정 부하(steady) 중에 dev 앱을 배포하거나 강제로 죽이고, 그 시각을 남긴다(#313 02 §2·§3).
#
#   scripts/load/availability-2-servers/stimulus.sh dev deploy --label A-0  [--delay 120]   # 진짜 dev 배포(cd-dev.yml 수동 실행)
#   scripts/load/availability-2-servers/stimulus.sh dev kill   --label A-0b [--delay 180]   # 앱 JVM 을 호스트에서 kill -9 (restart: always 가 다시 띄움)
#   ... --dry-run                                                                            # 아무것도 건드리지 않고 할 일만 찍는다
#
# --delay 초: 이 스크립트를 run-k6.sh 와 같은 순간에 띄우고, 그만큼 기다렸다가 자극을 넣는다.
#   배포는 버튼 → 실제 교체까지 ≈ 2분 30초(빌드 2분 · 배포 스크립트 1분 25초, 10-14 실측)라 기본 120초 = 교체가 부하 4~5분째.
# 남기는 것: dev 버킷 load-results/<label>/stimulus.env 와 docs/experiments/load-runs/<label>-stimulus.env
#   KIND · PRESSED_AT(버튼·kill 시각) · T0(배포: 배포 스크립트 SSM 명령 시작 · kill: kill 시각) · CONTAINER_STARTED_AT · HEALTHY_AT (모두 epoch 초)
# dev 쓰기: 배포는 같은 커밋 다시 배포, kill 은 앱 재시작. 끊는 법: 배포는 Actions 에서 취소, kill 은 되돌릴 것 없음(자동 재시작).
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

KIND=""; DELAY=""; DRY="false"
while [ $# -gt 0 ]; do
  case "$1" in
    deploy|kill) KIND="$1" ;;
    --delay) DELAY="$2"; shift ;;
    --dry-run) DRY="true" ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] && [ -n "$KIND" ] || { echo "deploy|kill 과 --label 이 필요하다." >&2; exit 1; }
[ -n "$DELAY" ] || { [ "$KIND" = deploy ] && DELAY=120 || DELAY=180; }

BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
RUNS_DIR="${LOAD_RUNS_DIR:-docs/experiments/load-runs}"; mkdir -p "$RUNS_DIR"
OUT="$RUNS_DIR/$REPORT_LABEL-stimulus.env"
APP_ID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-app" Name=instance-state-name,Values=running \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
[ "$APP_ID" != "None" ] || { echo "실행 중인 $RESOURCE_PREFIX-app 이 없다." >&2; exit 1; }

kst() { TZ=Asia/Seoul date -r "${1%.*}" '+%H:%M:%S'; }
say() { echo "[$(TZ=Asia/Seoul date '+%H:%M:%S')] $*" >&2; }

# 앱 서버에서 명령 하나를 돌리고 표준 출력을 돌려준다.
on_app() {
  local cid status
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$APP_ID" --document-name AWS-RunShellScript \
    --comment "availability $REPORT_LABEL" \
    --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$1")" \
    --query Command.CommandId --output text)
  for _ in $(seq 1 120); do
    sleep 2
    status=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$APP_ID" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Success|Failed|Cancelled|TimedOut) break ;; esac
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$APP_ID" \
    --query StandardOutputContent --output text
}

# 새 컨테이너가 뜬 시각과 앱이 요청을 받을 준비가 된 시각. healthy 가 될 때까지 기다린 뒤(최대 5분) 준비 시각은 앱 로그의
# "Tomcat started" 에서 읽는다 — 배포는 실행이 끝난 뒤에야 여기 오므로 기다린 시각을 쓰면 늦게 찍힌다(A-0-1 에서 2분 넘게 어긋남).
CONTAINER_TIMES='started=$(date -d "$(docker inspect -f {{.State.StartedAt}} wes-app)" +%s.%N)
for i in $(seq 1 150); do [ "$(docker inspect -f {{.State.Health.Status}} wes-app 2>/dev/null)" = healthy ] && break; sleep 2; done
ready=$(docker logs wes-app 2>&1 | grep "Tomcat started" | tail -1 | cut -c1-29)
echo "CONTAINER_STARTED_AT=$started"; echo "HEALTHY_AT=$(date -d "$ready" +%s.%N)"'

if [ "$DRY" = "true" ]; then
  say "dry-run · $KIND · label $REPORT_LABEL · $DELAY 초 뒤 · 앱 $APP_ID"
  [ "$KIND" = deploy ] && say "  gh workflow run cd-dev.yml --ref develop → 실행 끝까지 대기 → SSM 명령(comment 'wes-api dev deploy …') 시작 시각 = T0"
  [ "$KIND" = kill ] && say "  SSM: kill -9 \$(docker inspect -f {{.State.Pid}} wes-app) → 그 시각 = T0 → healthy 까지 대기"
  say "  기록 → $OUT · s3://$BUCKET/load-results/$REPORT_LABEL/stimulus.env"
  exit 0
fi

say "$KIND 자극 대기 $DELAY 초 (앱 $APP_ID)"
sleep "$DELAY"

if [ "$KIND" = deploy ]; then
  PRESSED_AT=$(date +%s)
  gh workflow run cd-dev.yml --ref develop
  say "배포 버튼 누름 — 실행 찾는 중"
  RUN_ID=""
  for _ in $(seq 1 30); do
    sleep 3
    RUN_ID=$(gh run list --workflow cd-dev.yml --event workflow_dispatch --limit 1 --json databaseId,createdAt \
      -q ".[] | select((.createdAt | fromdateiso8601) >= $PRESSED_AT - 5) | .databaseId")
    [ -n "$RUN_ID" ] && break
  done
  [ -n "$RUN_ID" ] || { echo "방금 누른 배포 실행을 못 찾았다." >&2; exit 1; }
  say "실행 $RUN_ID — 끝날 때까지 대기"
  gh run watch "$RUN_ID" --interval 10 --exit-status >/dev/null || say "배포 실행이 실패로 끝났다 — 그래도 시각은 남긴다"
  AFTER=$(date -u -r "$PRESSED_AT" '+%Y-%m-%dT%H:%M:%SZ')
  # 결과가 페이지마다 따로 와서 [0] 이 여러 줄이 된다 — 전부 받아 누른 뒤 가장 이른 배포 명령을 고른다.
  T0=$(aws ssm list-commands --region "$REGION" --instance-id "$APP_ID" \
    --filters "key=InvokedAfter,value=$AFTER" "key=DocumentName,value=AWS-RunShellScript" \
    --query "Commands[?starts_with(Comment, 'wes-api dev deploy')].RequestedDateTime" --output json \
    | python3 -c 'import sys,json,datetime; t=[datetime.datetime.fromisoformat(x).timestamp() for x in json.load(sys.stdin)]; print(min(t) if t else "")')
  [ -n "$T0" ] || { echo "배포 SSM 명령을 못 찾았다(InvokedAfter $AFTER)." >&2; exit 1; }
  TIMES=$(on_app "$CONTAINER_TIMES")
else
  # SSM 에이전트(snap)는 AppArmor 프로필 안에서 돌아 docker-default 컨테이너에 신호를 못 보낸다(kill: Permission denied, A-0b 첫 시도).
  # systemd-run 으로 프로필 밖에서 보낸다.
  KILLED=$(on_app 'pid=$(docker inspect -f {{.State.Pid}} wes-app); at=$(date +%s.%N); systemd-run --quiet --wait /bin/kill -9 "$pid" && echo "KILLED_AT=$at"')
  PRESSED_AT=$(echo "$KILLED" | sed -n 's/^KILLED_AT=//p')
  [ -n "$PRESSED_AT" ] || { echo "kill 시각을 못 받았다: $KILLED" >&2; exit 1; }
  T0="$PRESSED_AT"
  say "kill -9 보냄 — 다시 healthy 까지 대기"
  sleep 5
  TIMES=$(on_app "$CONTAINER_TIMES")
fi

STARTED=$(echo "$TIMES" | sed -n 's/^CONTAINER_STARTED_AT=//p')
HEALTHY=$(echo "$TIMES" | sed -n 's/^HEALTHY_AT=//p')
# 컨테이너가 T0 전에 뜬 그대로면 자극이 안 먹은 것이다 — 그 회차는 버린다.
python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) > float(sys.argv[2]) else 1)' "$STARTED" "$T0" \
  || { echo "새 컨테이너가 T0 뒤에 뜨지 않았다(시작 $(kst "$STARTED")) — 자극이 먹지 않았다." >&2; exit 1; }
{
  echo "KIND=$KIND"
  echo "PRESSED_AT=$PRESSED_AT"
  echo "T0=$T0"
  echo "CONTAINER_STARTED_AT=$STARTED"
  echo "HEALTHY_AT=$HEALTHY"
} > "$OUT"
aws s3 cp --only-show-errors --region "$REGION" "$OUT" "s3://$BUCKET/load-results/$REPORT_LABEL/stimulus.env"
say "기록 $OUT"
say "  누름 $(kst "$PRESSED_AT") · T0 $(kst "$T0") · 새 컨테이너 $(kst "$STARTED") · healthy $(kst "$HEALTHY")"
