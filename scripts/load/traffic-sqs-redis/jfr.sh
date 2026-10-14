#!/usr/bin/env bash
# 앱 CPU 프로파일(JFR) — dev 공개 API 를 Flight Recorder 와 함께 다시 띄우고, 회차 뒤 녹화를 받아 어디서 CPU·할당을 쓰는지 요약한다.
# B-1″에서 벽이 DB 에서 앱 CPU(400명 단계 92~98%)로 옮겨 가 03 (o) 판단 전에 CPU 위치를 본다(#286 2-0).
#
#   scripts/load/traffic-sqs-redis/jfr.sh dev on  --label 2-0                        # 회차 전: 앱 재시작(≈ 1분)
#   scripts/load/traffic-sqs-redis/jfr.sh dev status
#   scripts/load/traffic-sqs-redis/jfr.sh dev off                                    # 회차 뒤: 녹화를 파일로 쓰고 원래 설정으로 재시작
#   scripts/load/traffic-sqs-redis/jfr.sh dev fetch --label 2-0 [--since 14:26 --until 14:31]   # 받아서 요약(시각 KST, 구간만 보려면)
#
# 녹화는 서버 볼륨(/opt/wes-prod/logs/jfr)에 쓴다 — 컨테이너 /tmp(64MB tmpfs)에 쓰면 차서 JVM 이 내려간다(10-13 R-3-c).
# on 은 compose 덮어쓰기 파일(docker-compose.jfr.yml)로 JAVA_TOOL_OPTIONS 에 녹화 옵션만 더한다. 다음 dev 배포는 덮어쓰기 없이
# 띄우므로 녹화가 저절로 꺼진다(녹화 파일은 dumponexit 으로 남는다). 앱이 2분 안에 healthy 가 아니면 원래 설정으로 되돌린다.
# 앱 재시작이 있어 dev 쓰기다. dev 전용.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }

ACTION="${1:-status}"; shift || true
SINCE=""; UNTIL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift ;;
    --until) UNTIL="$2"; shift ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
case "$ACTION" in on|off|status|fetch) ;; *) echo "on · off · status · fetch 중 하나: $ACTION" >&2; exit 1 ;; esac
if [ "$ACTION" = "on" ] || [ "$ACTION" = "fetch" ]; then
  [ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다(녹화 파일 이름)." >&2; exit 1; }
  [[ "$REPORT_LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "--label 은 영문·숫자·._- 만: $REPORT_LABEL" >&2; exit 1; }
fi

APP_ID=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-app" "Name=instance-state-name,Values=running" \
  --query "Reservations[0].Instances[0].InstanceId" --output text)
[ "$APP_ID" != "None" ] || { echo "$RESOURCE_PREFIX-app 이 떠 있지 않다." >&2; exit 1; }

# 원격 셸 한 덩이를 SSM 으로 돌리고 표준 출력을 돌려준다. 실패면 표준 오류까지 보여 주고 멈춘다.
# SSM 은 sh(dash)로 돌려 pipefail 이 없다 — base64 로 감싸 bash 에 넘긴다(따옴표도 안 깨진다).
ssm_run() {
  local cid status i remote
  remote="echo $(printf '%s' "$1" | base64 | tr -d '\n') | base64 -d | bash"
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$APP_ID" --document-name AWS-RunShellScript \
    --timeout-seconds 600 --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]],"executionTimeout":["600"]}))' "$remote")" \
    --query Command.CommandId --output text)
  for i in $(seq 1 200); do
    sleep 3
    status=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$APP_ID" --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Success|Failed|Cancelled|TimedOut) break ;; esac
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$APP_ID" --query StandardOutputContent --output text
  if [ "$status" != "Success" ]; then
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$APP_ID" --query StandardErrorContent --output text >&2
    echo "원격 실행 실패: $status" >&2; exit 1
  fi
}

# 원격 공통 — 지금 컨테이너의 이미지·JVM 옵션을 읽고, 배포 스크립트처럼 compose 변수를 채운다(서버에 .env 가 없다).
REMOTE_COMMON='set -euo pipefail
cd /opt/wes-prod
ENVS=$(docker inspect wes-app --format "{{range .Config.Env}}{{println .}}{{end}}")
IMAGE=$(docker inspect wes-app --format "{{.Config.Image}}")
export IMAGE_TAG="${IMAGE##*:}" OWNER_LOWERCASE=$(echo "$IMAGE" | cut -d/ -f2) LOKI_URL=""
recording_on() { echo "$ENVS" | grep -q StartFlightRecording; }
wait_healthy() {
  for i in $(seq 1 40); do
    [ "$(docker inspect -f "{{.State.Health.Status}}" wes-app 2>/dev/null)" = healthy ] && return 0
    sleep 3
  done
  return 1
}
compose_base() { docker compose -f docker-compose.prod.yml up -d --no-deps wes-api 2>&1 | tail -3; }'

case "$ACTION" in
status)
  ssm_run "$REMOTE_COMMON
echo \"이미지 \$IMAGE_TAG · 상태 \$(docker inspect -f '{{.State.Health.Status}} (시작 {{.State.StartedAt}})' wes-app)\"
if recording_on; then echo \"녹화 켜짐: \$(echo \"\$ENVS\" | grep -o 'StartFlightRecording=[^ ]*')\"; else echo '녹화 꺼짐'; fi
[ -f docker-compose.jfr.yml ] && echo '덮어쓰기 파일 있음(docker-compose.jfr.yml)'
ls -la logs/jfr 2>/dev/null || echo 'logs/jfr 없음'
df -h / | tail -1"
  ;;

on)
  # profile 설정: 메서드 표본 10ms · 할당 표본 · 잠금 경합. stackdepth 128 — Spring·Hibernate 스택은 64(기본)로는 우리 코드까지 안 닿는다.
  JFR_OPTS="-XX:FlightRecorderOptions=stackdepth=128,repository=/var/log/spring/jfr/repo -XX:StartFlightRecording=name=wes,settings=profile,disk=true,maxsize=1g,maxage=3h,dumponexit=true,filename=/var/log/spring/jfr/$REPORT_LABEL.jfr"
  ssm_run "$REMOTE_COMMON
if recording_on; then echo '이미 녹화 중이다 — 먼저 off.' >&2; exit 1; fi
BASE=\$(echo \"\$ENVS\" | sed -n 's/^JAVA_TOOL_OPTIONS=//p')
[ -n \"\$BASE\" ] || { echo 'JAVA_TOOL_OPTIONS 를 못 읽었다.' >&2; exit 1; }
mkdir -p logs/jfr && rm -rf logs/jfr/repo && chown -R 10001:10001 logs/jfr
cat > docker-compose.jfr.yml <<EOF
# jfr.sh on 이 만든 임시 덮어쓰기 — off 가 지운다.
services:
  wes-api:
    stop_grace_period: 90s
    environment:
      - JAVA_TOOL_OPTIONS=\$BASE $JFR_OPTS
EOF
docker compose -f docker-compose.prod.yml -f docker-compose.jfr.yml up -d --no-deps wes-api 2>&1 | tail -3
if ! wait_healthy; then
  echo '녹화 옵션으로 healthy 가 안 됐다 — 원래 설정으로 되돌린다.' >&2
  docker logs --tail 30 wes-app >&2 || true
  rm -f docker-compose.jfr.yml; compose_base; wait_healthy || true
  exit 1
fi
# JFR 은 모르는 옵션을 경고만 하고 넘어간다 — repository 가 무시되면 조각이 /tmp(64MB)로 가서 JVM 이 내려간다. 볼륨에 조각이 있어야 계속한다.
sleep 5
if docker logs wes-app 2>&1 | grep -q 'jfr,start.*doesn.t exist' || [ -z \"\$(ls -A logs/jfr/repo 2>/dev/null)\" ]; then
  echo '녹화 조각이 볼륨(logs/jfr/repo)에 없다 — 원래 설정으로 되돌린다.' >&2
  docker logs wes-app 2>&1 | grep -i 'jfr' | head -5 >&2 || true
  rm -f docker-compose.jfr.yml; compose_base; wait_healthy || true
  exit 1
fi
echo \"녹화 시작: \$IMAGE_TAG · 파일 logs/jfr/$REPORT_LABEL.jfr (off 때 씀)\""
  echo "끝나면: $0 dev off → $0 dev fetch --label $REPORT_LABEL"
  ;;

off)
  ssm_run "$REMOTE_COMMON
if ! recording_on; then echo '녹화 중이 아니다(이미 꺼짐).'; ls -la logs/jfr 2>/dev/null; exit 0; fi
# 멈출 때 JVM 이 녹화를 파일로 쓴다(dumponexit). 기본 10초면 잘릴 수 있어 넉넉히 기다린다.
docker stop -t 90 wes-app >/dev/null
rm -f docker-compose.jfr.yml
compose_base
wait_healthy || { echo '원래 설정으로 healthy 가 안 됐다 — docker logs wes-app 확인.' >&2; exit 1; }
echo '녹화 끄고 원래 설정으로 재시작 ✔'
ls -la logs/jfr"
  ;;

fetch)
  BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
  S3_KEY="s3://$BUCKET/load-results/$REPORT_LABEL/app.jfr"
  RUNS_DIR="${LOAD_RUNS_DIR:-docs/experiments/load-runs}"; mkdir -p "$RUNS_DIR"
  LOCAL_JFR="$RUNS_DIR/$REPORT_LABEL-app.jfr"
  if [ ! -s "$LOCAL_JFR" ]; then
    ssm_run "set -euo pipefail
F=/opt/wes-prod/logs/jfr/$REPORT_LABEL.jfr
[ -s \"\$F\" ] || { echo \"\$F 가 없다 — off 를 먼저(녹화는 멈출 때 파일로 쓴다).\" >&2; exit 1; }
aws s3 cp --only-show-errors --region $REGION \"\$F\" $S3_KEY
rm -f \"\$F\"; rm -rf /opt/wes-prod/logs/jfr/repo" >/dev/null
    aws s3 cp --only-show-errors --region "$REGION" "$S3_KEY" "$LOCAL_JFR"
  fi
  JFR_BIN="$(/usr/libexec/java_home -v 21 2>/dev/null)/bin/jfr"
  [ -x "$JFR_BIN" ] || JFR_BIN="$(command -v jfr || true)"
  [ -n "$JFR_BIN" ] || { echo "jfr 도구(JDK 21)가 없다. 녹화는 $LOCAL_JFR 에 받았다." >&2; exit 1; }

  DB_NAME="DB 안 씀"
  report_begin "앱 CPU 프로파일(JFR)" "$APP_ID · $LOCAL_JFR ($(du -h "$LOCAL_JFR" | cut -f1))${SINCE:+ · 구간 $SINCE~$UNTIL KST}"

  report_section "1. 녹화 범위" "jfr summary — 시작·길이·이벤트 수 상위"
  "$JFR_BIN" summary "$LOCAL_JFR" | sed -n '1,12p' | sed 's/^/    /'

  report_section "2. CPU 표본 — 어느 스레드 · 어느 라이브러리 · 우리 코드 어디 아래" \
    "jdk.ExecutionSample(10ms) 를 센다. 라이브러리 = 스택 맨 위(지금 실행 중) 프레임의 패키지 · 우리 코드 = 맨 위에서 가장 가까운 com.soma.wes 프레임"
  # 시각은 jfr 가 현지 시각으로 찍는다 — --since·--until(KST)과 맞추려고 KST 로 고정한다.
  TZ=Asia/Seoul "$JFR_BIN" print --events jdk.ExecutionSample --stack-depth 128 "$LOCAL_JFR" \
    | python3 "$SCRIPT_DIR/jfr_samples.py" "${SINCE:-}" "${UNTIL:-}"

  report_section "3. 메서드 상위" "jfr view hot-methods — 스택 맨 위 메서드(전체 녹화)"
  "$JFR_BIN" view --width 170 hot-methods "$LOCAL_JFR" | sed 's/^/    /'

  report_section "4. 할당 상위" "jfr view allocation-by-site — 어디서 객체를 많이 만드나(GC 일감의 출처)"
  "$JFR_BIN" view --width 170 allocation-by-site "$LOCAL_JFR" | sed 's/^/    /'

  report_section "5. GC" "jfr view gc — 멈춤 합·최대. 앱 CPU 중 GC 몫 판단에 쓴다"
  "$JFR_BIN" view --width 170 gc-pauses "$LOCAL_JFR" | sed 's/^/    /'

  report_section "6. 잠금 경합" "jfr view contention-by-site — 스레드가 모니터를 기다린 곳"
  "$JFR_BIN" view --width 170 contention-by-site "$LOCAL_JFR" | sed 's/^/    /'
  report_end
  ;;
esac
