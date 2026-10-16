#!/usr/bin/env bash
# 가용성 회차의 자극 — k6 일정 부하(steady) 중에 dev 앱을 배포하거나 한 대를 강제로 죽이고, 그동안 서버들이 어땠는지 남긴다(#313 02 §2·§3, 05 W4).
#
#   scripts/load/availability-2-servers/stimulus.sh dev deploy --label A-0′-1 [--delay 120]                # 진짜 dev 배포(cd-dev.yml = 서버마다 한 대씩)
#   scripts/load/availability-2-servers/stimulus.sh dev kill   --label A-1-1  [--delay 180] [--target follower|leader|i-…]
#                                                                       # 한 대의 앱 JVM 을 kill -9 (기본: 리더가 아닌 쪽)
#   scripts/load/availability-2-servers/stimulus.sh dev watch  --label A-2m   [--duration 630]           # 자극 없이 관찰만(A-2m 메모리 관문)
#   ... --dry-run                                                                                          # 아무것도 건드리지 않고 할 일만 찍는다
#
# --delay 초: 이 스크립트를 run-k6.sh 와 같은 순간에 띄우고, 그만큼 기다렸다가 자극을 넣는다(watch 는 0).
#   배포는 버튼 → 첫 서버 교체까지 ≈ 2분 30초라 기본 120초. 롤링은 서버당 ≈ 2분 15초라 10분 부하 안에 두 대가 다 들어간다.
# 그동안 늘 남기는 것(자극과 무관 — 서버가 몇 대든):
#   targets.tsv  2초마다 ALB 대상 그룹의 서버별 상태          servers.tsv  30초마다 서버별 남은 메모리·스왑·java RSS·스왑 입출력
#   events.tsv   ASG 활동(standby·복귀·종료·새 서버) · 배포 명령 · 새 컨테이너 · 준비 · kill · 리더 넘김
#   servers.txt  끝날 때 서버별 GC 멈춤(최대·1초 넘는 수, 이 회차 구간만) · 리더 여부 · 메모리
#   stimulus.env KIND · PRESSED_AT · T0 · ENDED_AT(자극이 끝난 시각 — 배포 실행 끝 / kill 뒤 대상이 다 healthy + slow start) · …
# 위치: docs/experiments/load-runs/<label>-*.{env,tsv,txt} 와 dev 버킷 load-results/<label>/
# dev 쓰기: 배포는 같은 커밋 다시 배포, kill 은 앱 재시작(ASG 가 서버를 갈 수도 있다). 끊는 법: 배포는 Actions 에서 취소, kill 은 되돌릴 것 없음.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

KIND=""; DELAY=""; DURATION="630"; TARGET="follower"; DRY="false"
while [ $# -gt 0 ]; do
  case "$1" in
    deploy|kill|watch) KIND="$1" ;;
    --delay) DELAY="$2"; shift ;;
    --duration) DURATION="$2"; shift ;;
    --target) TARGET="$2"; shift ;;
    --dry-run) DRY="true" ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] && [ -n "$KIND" ] || { echo "deploy|kill|watch 와 --label 이 필요하다." >&2; exit 1; }
case "$KIND" in deploy) DELAY="${DELAY:-120}" ;; kill) DELAY="${DELAY:-180}" ;; watch) DELAY="${DELAY:-0}" ;; esac

BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
RUNS_DIR="${LOAD_RUNS_DIR:-docs/experiments/load-runs}"; mkdir -p "$RUNS_DIR"
BASE="$RUNS_DIR/$REPORT_LABEL"
APP_NAME="$RESOURCE_PREFIX-app"
# ALB 가 새 서버를 서서히 올리는 시간(대상 그룹 slow start, infra #112). 자극이 끝난 시각을 여기까지 늘려 잡는다.
SLOW_START_SECONDS=60

# epoch → 시각. macOS(BSD) date 는 -r, Linux(GNU) date 는 -d @ 로 읽는다.
from_epoch() { date -r "$1" "$2" 2>/dev/null || date -d "@$1" "$2"; }
kst() { TZ=Asia/Seoul from_epoch "${1%.*}" '+%H:%M:%S'; }
say() { echo "[$(TZ=Asia/Seoul date '+%H:%M:%S')] $*" >&2; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

running_apps() {
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=$APP_NAME" Name=instance-state-name,Values=running \
    --query 'Reservations[].Instances[].InstanceId' --output text | tr '\t' '\n' | sort
}
APP_IDS=$(running_apps)
[ -n "$APP_IDS" ] || { echo "실행 중인 $APP_NAME 이 없다." >&2; exit 1; }
# 서버가 ASG 소속이면 그 대상 그룹을, 아니면(옛 단일 서버) 이름이 같은 대상 그룹을 본다.
ASG_NAME=$(aws autoscaling describe-auto-scaling-instances --region "$REGION" --instance-ids $APP_IDS \
  --query 'AutoScalingInstances[0].AutoScalingGroupName' --output text 2>/dev/null || echo None)
[ "$ASG_NAME" != "None" ] || ASG_NAME=""
if [ -n "$ASG_NAME" ]; then
  TG_ARN=$(aws autoscaling describe-auto-scaling-groups --region "$REGION" --auto-scaling-group-names "$ASG_NAME" \
    --query 'AutoScalingGroups[0].TargetGroupARNs[0]' --output text)
else
  TG_ARN=$(aws elbv2 describe-target-groups --region "$REGION" --names "$APP_NAME" --query 'TargetGroups[0].TargetGroupArn' --output text)
fi

# 앱 서버 한 대에서 명령 하나를 돌리고 표준 출력을 돌려준다. 서버가 그새 사라졌으면(ASG 교체) 빈 출력.
on_app() {
  local id="$1" cid status
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$id" --document-name AWS-RunShellScript \
    --comment "availability $REPORT_LABEL" \
    --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$2")" \
    --query Command.CommandId --output text 2>/dev/null) || return 0
  # CONTAINER_TIMES 가 healthy 를 최대 300초 기다리므로 그보다 길게(360초) 본다 — 짧으면 끝나기 전 출력을 읽어 시각이 빈다.
  for _ in $(seq 1 180); do
    sleep 2
    status=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$id" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Success|Failed|Cancelled|TimedOut|Undeliverable|Terminated) break ;; esac
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$id" \
    --query StandardOutputContent --output text 2>/dev/null || true
}

event() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$BASE-events.tsv"; }

# 새 컨테이너가 뜬 시각과 앱이 요청을 받을 준비가 된 시각. healthy 가 될 때까지 기다린 뒤(최대 5분) 준비 시각은 앱 로그의
# "Tomcat started" 에서 읽는다 — 배포는 실행이 끝난 뒤에야 여기 오므로 기다린 시각을 쓰면 늦게 찍힌다(A-0-1 에서 2분 넘게 어긋남).
CONTAINER_TIMES='started=$(date -d "$(docker inspect -f {{.State.StartedAt}} wes-app)" +%s.%N)
for i in $(seq 1 150); do [ "$(docker inspect -f {{.State.Health.Status}} wes-app 2>/dev/null)" = healthy ] && break; sleep 2; done
ready=$(docker logs wes-app 2>&1 | grep "Tomcat started" | tail -1 | cut -c1-29)
echo "CONTAINER_STARTED_AT=$started"; echo "HEALTHY_AT=$(date -d "$ready" +%s.%N)"'

# 리더 여부 — 리더 임대(RedisSchedulerLeadership)의 마지막 얻음·잃음 로그. acquired 면 리더.
LEADER_STATE='docker logs wes-app 2>&1 | grep -oE "event=leader\.(acquired|lost|released|started)" | tail -1 | sed "s/event=leader\.//"'

# 서버 한 대의 메모리 표본 한 줄: 남은 메모리 · 스왑 사용 · java RSS · java 스왑(MB) · 1초 동안 스왑 입·출(페이지).
MEM_SAMPLE='p=$(docker inspect -f {{.State.Pid}} wes-app 2>/dev/null)
avail=$(free -m | awk "/^Mem/{print \$7}"); swap=$(free -m | awk "/^Swap/{print \$3}")
rss=$(awk "/VmRSS/{print int(\$2/1024)}" /proc/$p/status 2>/dev/null); jswap=$(awk "/VmSwap/{print int(\$2/1024)}" /proc/$p/status 2>/dev/null)
io=$(vmstat 1 2 | tail -1 | awk "{print \$7\" \"\$8}")
echo "$avail $swap ${rss:--} ${jswap:--} $io"'

# 이 회차 구간 [시작, 끝](epoch)의 GC 멈춤 — 최대(ms) · 1초 넘는 수 · 전체 수 · 가장 긴 것의 시각과 종류. gc.log 는 호스트에 남는다(compose 의 ./logs).
gc_summary() {
  cat <<EOF
python3 - $1 $2 <<'PY'
import datetime, glob, re, sys
lo, hi = float(sys.argv[1]), float(sys.argv[2])
KST = datetime.timezone(datetime.timedelta(hours=9))
pat = re.compile(r"^\[([^\]]+)\].*\] GC\(\d+\) (Pause [A-Za-z]+(?: \([^)]*\))?).* ([0-9.]+)ms\$")
worst, over, n = (0.0, "", ""), 0, 0
for path in glob.glob("/opt/wes-prod/logs/gc.log*"):
    for line in open(path, errors="replace"):
        m = pat.match(line.strip())
        if not m:
            continue
        at = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
        if not lo <= at <= hi:
            continue
        ms = float(m.group(3)); n += 1; over += ms > 1000
        if ms > worst[0]:
            worst = (ms, datetime.datetime.fromtimestamp(at, KST).strftime("%H:%M:%S"), m.group(2))
print(f"GC {worst[0]:.0f} {over} {n} {worst[1] or '-'} {worst[2].replace(' ', '_') or '-'}")
PY
EOF
}

if [ "$DRY" = "true" ]; then
  say "dry-run · $KIND · label $REPORT_LABEL · $DELAY 초 뒤 · 서버 $(echo $APP_IDS) · ASG ${ASG_NAME:-없음} · 대상 그룹 ${TG_ARN##*/}"
  for id in $APP_IDS; do say "  $id 리더 임대: $(on_app "$id" "$LEADER_STATE")"; done
  case "$KIND" in
    deploy) say "  gh workflow run cd-dev.yml --ref develop → 실행 끝까지 대기 → T0 = 첫 standby·배포 명령 중 이른 것 → 서버마다 컨테이너 시각" ;;
    kill) say "  --target $TARGET 인 서버에 SSM: systemd-run kill -9 → 대상 그룹이 다 healthy 로 돌아올 때까지(ASG 교체 포함, 최대 10분) + slow start ${SLOW_START_SECONDS}초" ;;
    watch) say "  자극 없이 $DURATION 초 관찰" ;;
  esac
  say "  기록 → $BASE-{stimulus.env,events.tsv,targets.tsv,servers.tsv,servers.txt} · s3://$BUCKET/load-results/$REPORT_LABEL/"
  exit 0
fi

rm -f "$BASE-events.tsv" "$BASE-targets.tsv" "$BASE-servers.tsv"
START_AT=$(now)

# 표본 — 끝날 때 끈다. 대상 그룹은 2초마다(감지 5초 × 2 를 초 단위로 보려고), 메모리는 SSM 왕복이 있어 30초마다.
(
  while true; do
    line=$(aws elbv2 describe-target-health --region "$REGION" --target-group-arn "$TG_ARN" \
      --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State]' --output text 2>/dev/null | awk '{printf "%s:%s ", $1, $2}')
    printf '%s\t%s\n' "$(now)" "$line" >> "$BASE-targets.tsv"
    sleep 2
  done
) &
TARGETS_PID=$!
(
  while true; do
    for id in $(running_apps); do
      sample=$(on_app "$id" "$MEM_SAMPLE")
      [ -n "$sample" ] && printf '%s\t%s\t%s\n' "$(now)" "$id" "$sample" >> "$BASE-servers.tsv"
    done
    sleep 30
  done
) &
MEM_PID=$!
trap 'kill $TARGETS_PID $MEM_PID 2>/dev/null || true' EXIT

say "$KIND · 서버 $(echo $APP_IDS) · 대기 $DELAY 초"
sleep "$DELAY"

TARGET_ID=""; TARGET_ROLE=""; STARTED=""; HEALTHY=""
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
  say "실행 $RUN_ID — 끝날 때까지 대기(서버마다 한 대씩)"
  gh run watch "$RUN_ID" --interval 10 --exit-status >/dev/null || say "배포 실행이 실패로 끝났다 — 그래도 시각은 남긴다"
  ENDED_AT=$(now)
  AFTER=$(TZ=UTC from_epoch "$PRESSED_AT" '+%Y-%m-%dT%H:%M:%SZ')
  # 서버마다 배포 명령이 따로다(rolling-deploy.sh). 결과가 페이지마다 따로 와서 줄이 여럿이 된다 — 전부 받아 시각순으로.
  aws ssm list-commands --region "$REGION" \
    --filters "key=InvokedAfter,value=$AFTER" "key=DocumentName,value=AWS-RunShellScript" \
    --query "Commands[?starts_with(Comment, 'wes-api dev deploy')].[RequestedDateTime,InstanceIds[0]]" --output json \
    | python3 -c 'import sys,json,datetime
for at, i in json.load(sys.stdin): print(f"{datetime.datetime.fromisoformat(at).timestamp():.3f}\t{i}\tdeploy_command")' \
    >> "$BASE-events.tsv"
  grep -q deploy_command "$BASE-events.tsv" 2>/dev/null || { echo "배포 SSM 명령을 못 찾았다(InvokedAfter $AFTER)." >&2; exit 1; }
  for id in $(running_apps); do
    TIMES=$(on_app "$id" "$CONTAINER_TIMES")
    s=$(echo "$TIMES" | sed -n 's/^CONTAINER_STARTED_AT=//p'); h=$(echo "$TIMES" | sed -n 's/^HEALTHY_AT=//p')
    [ -n "$s" ] && event "$s" "$id" container_started
    [ -n "$h" ] && event "$h" "$id" app_ready
    # 첫 서버의 시각을 요약 칸에 둔다(1대 회차와 같은 칸).
    if [ -z "$STARTED" ] || python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) < float(sys.argv[2]) else 1)' "${s:-9e9}" "$STARTED"; then
      STARTED="$s"; HEALTHY="$h"
    fi
  done
else
  if [ "$KIND" = kill ]; then
    case "$TARGET" in
      i-*) TARGET_ID="$TARGET" ;;
      leader|follower)
        for id in $APP_IDS; do
          state=$(on_app "$id" "$LEADER_STATE")
          if [ "$TARGET" = leader ] && [ "$state" = acquired ]; then TARGET_ID="$id"; break; fi
          if [ "$TARGET" = follower ] && [ "$state" != acquired ]; then TARGET_ID="$id"; break; fi
        done ;;
      *) echo "--target 은 follower|leader|i-… 이다." >&2; exit 1 ;;
    esac
    [ -n "$TARGET_ID" ] || { echo "--target $TARGET 에 맞는 서버가 없다(서버 $(echo $APP_IDS))." >&2; exit 1; }
    TARGET_ROLE=$(on_app "$TARGET_ID" "$LEADER_STATE"); [ "$TARGET_ROLE" = acquired ] && TARGET_ROLE=leader || TARGET_ROLE=follower
    BEFORE_HEALTHY=$(aws elbv2 describe-target-health --region "$REGION" --target-group-arn "$TG_ARN" \
      --query 'length(TargetHealthDescriptions[?TargetHealth.State==`healthy`])' --output text)
    # SSM 에이전트(snap)는 AppArmor 프로필 안에서 돌아 docker-default 컨테이너에 신호를 못 보낸다(kill: Permission denied, A-0b 첫 시도).
    # systemd-run 으로 프로필 밖에서 보낸다.
    KILLED=$(on_app "$TARGET_ID" 'pid=$(docker inspect -f {{.State.Pid}} wes-app); at=$(date +%s.%N); systemd-run --quiet --wait /bin/kill -9 "$pid" && echo "KILLED_AT=$at"')
    PRESSED_AT=$(echo "$KILLED" | sed -n 's/^KILLED_AT=//p')
    [ -n "$PRESSED_AT" ] || { echo "kill 시각을 못 받았다: $KILLED" >&2; exit 1; }
    event "$PRESSED_AT" "$TARGET_ID" "killed_$TARGET_ROLE"
    say "kill -9 → $TARGET_ID ($TARGET_ROLE) — 대상 그룹이 다시 healthy $BEFORE_HEALTHY 대가 될 때까지(ASG 가 갈면 새 서버까지, 최대 10분)"
    # 컨테이너가 다시 뜨기 전에 ALB 가 빼면 ASG 가 서버를 통째로 갈 수 있다(10-16 01:39). 그래서 그 서버가 아니라 대상 그룹 전체를 본다.
    sleep 30
    for _ in $(seq 1 120); do
      read -r healthy total < <(aws elbv2 describe-target-health --region "$REGION" --target-group-arn "$TG_ARN" \
        --query '[length(TargetHealthDescriptions[?TargetHealth.State==`healthy`]), length(TargetHealthDescriptions)]' --output text)
      [ "$healthy" -ge "$BEFORE_HEALTHY" ] && [ "$healthy" -eq "$total" ] && break
      sleep 5
    done
    event "$(now)" "-" all_targets_healthy
    ENDED_AT=$(python3 -c "import time; print(f'{time.time() + $SLOW_START_SECONDS:.3f}')")
    if aws ec2 describe-instances --region "$REGION" --instance-ids "$TARGET_ID" \
         --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null | grep -q running; then
      TIMES=$(on_app "$TARGET_ID" "$CONTAINER_TIMES")
      STARTED=$(echo "$TIMES" | sed -n 's/^CONTAINER_STARTED_AT=//p'); HEALTHY=$(echo "$TIMES" | sed -n 's/^HEALTHY_AT=//p')
      [ -n "$STARTED" ] && event "$STARTED" "$TARGET_ID" container_started
      [ -n "$HEALTHY" ] && event "$HEALTHY" "$TARGET_ID" app_ready
    fi
    # 리더를 죽였으면 다른 서버가 이어받은 시각(임대 ttl 30초를 기다림).
    if [ "$TARGET_ROLE" = leader ]; then
      for id in $(running_apps); do
        [ "$id" = "$TARGET_ID" ] && continue
        at=$(on_app "$id" 'docker logs wes-app 2>&1 | grep "event=leader.acquired" | tail -1 | cut -c1-29')
        [ -n "$at" ] || continue
        at=$(python3 -c 'import datetime,sys; print(datetime.datetime.fromisoformat(sys.argv[1]).timestamp())' "$at")
        python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) > float(sys.argv[2]) else 1)' "$at" "$PRESSED_AT" && event "$at" "$id" leader_acquired
      done
    fi
    # 자극 구간 끝(slow start)까지는 표본을 계속 남긴다.
    sleep "$SLOW_START_SECONDS"
  else
    PRESSED_AT="$START_AT"
    say "자극 없음 — $DURATION 초 관찰"
    sleep "$DURATION"
    ENDED_AT=$(now)
  fi
fi

# ASG 활동(standby·복귀·종료·새 서버·늘리기) — 자극을 누른 뒤의 것을 사건으로.
if [ -n "$ASG_NAME" ]; then
  aws autoscaling describe-scaling-activities --region "$REGION" --auto-scaling-group-name "$ASG_NAME" --max-items 30 \
    --query 'Activities[].[StartTime,EndTime,Description]' --output json \
    | python3 -c 'import sys,json,datetime,re
lo = float(sys.argv[1])
kinds = [("to Standby", "standby"), ("out of Standby", "in_service"), ("Terminating", "terminating"), ("Launching", "launching")]
for start, end, desc in json.load(sys.stdin):
    s = datetime.datetime.fromisoformat(start).timestamp()
    if s < lo:
        continue
    iid = (re.search(r"i-[0-9a-f]+", desc) or [None])[0] or "-"
    kind = next((k for t, k in kinds if t in desc), desc.split(":")[0].replace(" ", "_"))
    print(f"{s:.3f}\t{iid}\t{kind}")
    if end:
        print(f"{datetime.datetime.fromisoformat(end).timestamp():.3f}\t{iid}\t{kind}_done")' "$PRESSED_AT" >> "$BASE-events.tsv"
fi
touch "$BASE-events.tsv"
sort -n -o "$BASE-events.tsv" "$BASE-events.tsv"

# T0 = 자극이 서비스에 닿기 시작한 순간. 배포는 첫 standby(ALB 에서 뺌)·첫 배포 명령 중 이른 것, kill 은 kill 시각, watch 는 시작.
case "$KIND" in
  deploy) T0=$(awk -F'\t' '$3=="standby" || $3=="deploy_command" {print $1; exit}' "$BASE-events.tsv") ;;
  *) T0="$PRESSED_AT" ;;
esac
[ -n "$T0" ] || { echo "T0 를 정하지 못했다 — $BASE-events.tsv 를 보라." >&2; exit 1; }
if [ "$KIND" = kill ] && [ -n "$STARTED" ]; then
  # 컨테이너가 T0 전에 뜬 그대로면 자극이 안 먹은 것이다 — 그 회차는 버린다(ASG 가 서버를 갈았으면 이 검사는 건너뛴다).
  python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) > float(sys.argv[2]) else 1)' "$STARTED" "$T0" \
    || { echo "새 컨테이너가 T0 뒤에 뜨지 않았다(시작 $(kst "$STARTED")) — 자극이 먹지 않았다." >&2; exit 1; }
fi

# 끝 — 서버마다 이 회차 구간의 GC 멈춤 · 리더 여부 · 메모리.
kill $TARGETS_PID $MEM_PID 2>/dev/null || true
END_AT=$(now)
GC_MAX=0
# heredoc(PY) 끝줄 뒤에는 새 줄로 이어 써야 한다.
END_CMD="$(gc_summary "$START_AT" "$END_AT")"$'\n'"leader=\$($LEADER_STATE); echo \"LEADER \$leader\""$'\n''echo "MEM $(free -m | awk "/^Mem/{print \$7}") $(free -m | awk "/^Swap/{print \$3}")"'
{
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "서버" "리더 임대" "GC 최대(ms)" "1초 넘는 멈춤" "멈춤 수" "가장 긴 것" "종류" "남은 메모리(MB)" "스왑(MB)"
  for id in $(running_apps); do
    out=$(on_app "$id" "$END_CMD")
    read -r _ gmax gover gn gat gkind <<< "$(echo "$out" | grep '^GC ' || echo 'GC - - - - -')"
    read -r _ leader <<< "$(echo "$out" | grep '^LEADER' || echo 'LEADER -')"
    read -r _ avail swap <<< "$(echo "$out" | grep '^MEM' || echo 'MEM - -')"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "${leader:--}" "$gmax" "$gover" "$gn" "$gat" "$gkind" "$avail" "$swap"
    [[ "$gmax" =~ ^[0-9]+$ ]] && [ "$gmax" -gt "$GC_MAX" ] && GC_MAX="$gmax"
  done
} > "$BASE-servers.txt"

{
  echo "KIND=$KIND"
  echo "PRESSED_AT=$PRESSED_AT"
  echo "T0=$T0"
  echo "ENDED_AT=$ENDED_AT"
  echo "CONTAINER_STARTED_AT=$STARTED"
  echo "HEALTHY_AT=$HEALTHY"
  echo "TARGET_ID=$TARGET_ID"
  echo "TARGET_ROLE=$TARGET_ROLE"
  echo "GC_MAX_MS=$GC_MAX"
  echo "STARTED_AT=$START_AT"
} > "$BASE-stimulus.env"
for f in stimulus.env events.tsv targets.tsv servers.tsv servers.txt; do
  [ -f "$BASE-$f" ] && aws s3 cp --only-show-errors --region "$REGION" "$BASE-$f" "s3://$BUCKET/load-results/$REPORT_LABEL/$f"
done
say "기록 $BASE-{stimulus.env,events.tsv,targets.tsv,servers.tsv,servers.txt}"
say "  누름 $(kst "$PRESSED_AT") · T0 $(kst "$T0") · 끝 $(kst "$ENDED_AT")${TARGET_ID:+ · kill $TARGET_ID($TARGET_ROLE)} · GC 최대 ${GC_MAX}ms"
column -t -s $'\t' "$BASE-servers.txt" >&2
