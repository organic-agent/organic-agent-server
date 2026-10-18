#!/usr/bin/env bash
# 공개 API를 앱 서버마다 한 대씩 바꾼다(#313 ADR-0001) — CD 러너에서 돈다. 서버 안의 일은 deploy-public-ssm.sh 가 한다.
#
#   서버 한 대씩:  (ASG 소속이면) ALB 대상에서 등록 해제(진행 중 요청 마저 끝냄) → deploy-public-ssm.sh(SSM) → 다시 등록 → healthy
#                  → slow start 만큼 기다림
#   한 대라도 실패하면 거기서 멈춘다 — 그 서버는 스크립트가 직전 릴리스로 되돌렸고, 나머지 서버는 옛 버전으로 계속 받는다.
#
# ASG 가 아닌 서버(옛 단일 인스턴스)면 그 자리에서 바꾼다 — 예전과 같은 동작.
# standby 를 쓰지 않는다(10-18): ASG 를 on-demand 바닥 1 + spot 으로 섞자, on-demand 를 standby 로 빼는 순간 ASG 가 "on-demand 없음"으로
# 보고 새 on-demand 를 띄우며 받던 spot 을 바로 종료해 받을 서버가 0대가 됐다(dev 12:47, HealthyHostCount 0 · 5xx). ALB 대상에서만 빼면
# ASG 가 보는 대수·구성이 그대로라 갈아 끼우기가 생기지 않는다. 그동안 ASG 의 헬스 판정·교체·AZ 재조정·알람 처리를 멈춘다.
#
# 입력(환경 변수): INSTANCE_NAME_TAG · AWS_REGION · SLOW_START_SECONDS(기본 60)
#                  OWNER_LOWERCASE · IMAGE_TAG · PARAMETER_PREFIX · PUBLIC_IMAGE_BASENAME · IMAGE_REPOSITORY · GITHUB_ACTOR · GITHUB_TOKEN
#                  COMPOSE_FILE · ALLOY_FILE · DEPLOY_SCRIPT · COMMENT_PREFIX
set -euo pipefail

SLOW_START_SECONDS="${SLOW_START_SECONDS:-60}"
HEALTHY_WAIT_SECONDS=600
log() { echo "[$(date -u '+%H:%M:%S')] $*"; }

INSTANCES=$(aws ec2 describe-instances --region "$AWS_REGION" \
  --filters "Name=tag:Name,Values=${INSTANCE_NAME_TAG}" 'Name=instance-state-name,Values=running' \
  --query 'Reservations[].Instances[].InstanceId' --output text | tr '\t' '\n' | sort)
[ -n "$INSTANCES" ] || { echo "실행 중인 ${INSTANCE_NAME_TAG} 인스턴스가 없습니다" >&2; exit 1; }
log "대상: $(echo "$INSTANCES" | tr '\n' ' ')"

ASG_NAME=$(aws autoscaling describe-auto-scaling-instances --region "$AWS_REGION" \
  --instance-ids $INSTANCES --query 'AutoScalingInstances[0].AutoScalingGroupName' --output text 2>/dev/null || echo None)
[ "$ASG_NAME" != "None" ] || ASG_NAME=""

TARGET_GROUP_ARN=""
if [ -n "$ASG_NAME" ]; then
  TARGET_GROUP_ARN=$(aws autoscaling describe-auto-scaling-groups --region "$AWS_REGION" \
    --auto-scaling-group-names "$ASG_NAME" --query 'AutoScalingGroups[0].TargetGroupARNs[0]' --output text)
  log "ASG $ASG_NAME · 대상 그룹 $TARGET_GROUP_ARN"
fi

# 배포 동안 멈추는 ASG 처리. HealthCheck·ReplaceUnhealthy: 대상에서 뺀 서버를 헬스 실패로 보고 갈지 않게 · AZRebalance: 가용 영역을
# 맞춘다며 서버를 띄우고 지우지 않게(10-17 배포 중 실제로 일어남) · AlarmNotification: 늘리기·줄이기 알람이 대수를 바꾸지 않게.
# 이미 누가 멈춰 둔 것은 끝날 때 다시 켜지 않는다.
PAUSE_PROCESSES="HealthCheck ReplaceUnhealthy AZRebalance AlarmNotification"
PAUSED_BY_US=""
# 지금 대상에서 빼 둔 서버. 다시 등록하면 비운다.
OUT_OF_TARGET=""

target_state() {
  aws elbv2 describe-target-health --region "$AWS_REGION" --target-group-arn "$TARGET_GROUP_ARN" \
    --targets "Id=$1" --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text
}

# 끝날 때(실패해도) 되돌린다 — 빼 둔 서버를 먼저 다시 등록하고, 그다음 멈춘 처리를 켠다(반대면 헬스 판정이 빠진 서버를 갈 수 있다).
cleanup() {
  [ -n "$ASG_NAME" ] || return 0
  if [ -n "$OUT_OF_TARGET" ]; then
    aws elbv2 register-targets --region "$AWS_REGION" --target-group-arn "$TARGET_GROUP_ARN" \
      --targets "Id=$OUT_OF_TARGET" || echo "$OUT_OF_TARGET 대상 재등록 실패 — 손으로 register-targets" >&2
  fi
  if [ -n "$PAUSED_BY_US" ]; then
    # shellcheck disable=SC2086
    aws autoscaling resume-processes --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --scaling-processes $PAUSED_BY_US || echo "ASG 처리 재개 실패 — 손으로 resume-processes $PAUSED_BY_US" >&2
  fi
}
trap cleanup EXIT

wait_target_healthy() {
  local id="$1" state=""
  for _ in $(seq 1 $((HEALTHY_WAIT_SECONDS / 5))); do
    state=$(aws elbv2 describe-target-health --region "$AWS_REGION" --target-group-arn "$TARGET_GROUP_ARN" \
      --targets "Id=$id" --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text)
    [ "$state" = "healthy" ] && return 0
    sleep 5
  done
  echo "$id 가 ALB 에서 healthy 가 되지 않았습니다(마지막 $state)" >&2
  return 1
}

params_json() {
  local compose_b64 alloy_b64
  compose_b64=$(base64 -w0 "$COMPOSE_FILE")
  alloy_b64=$(base64 -w0 "$ALLOY_FILE")
  # GITHUB_TOKEN 은 잡 종료와 함께 만료되는 단기 토큰이다. jq 의 @sh 로 모든 주입값을 shell-safe 하게 인용한다.
  jq -n \
    --arg owner_lowercase "$OWNER_LOWERCASE" \
    --arg image_tag "$IMAGE_TAG" \
    --arg aws_region "$AWS_REGION" \
    --arg parameter_prefix "$PARAMETER_PREFIX" \
    --arg public_image_basename "$PUBLIC_IMAGE_BASENAME" \
    --arg image_repository "$IMAGE_REPOSITORY" \
    --arg github_actor "$GITHUB_ACTOR" \
    --arg github_token "$GITHUB_TOKEN" \
    --arg compose_b64 "$compose_b64" \
    --arg alloy_b64 "$alloy_b64" \
    --rawfile script "$DEPLOY_SCRIPT" \
    '
      def env_line($name; $value):
        "export \($name)=\($value | @sh)\n";
      {
        commands: [
          env_line("OWNER_LOWERCASE"; $owner_lowercase)
          + env_line("IMAGE_TAG"; $image_tag)
          + env_line("AWS_REGION"; $aws_region)
          + env_line("PARAMETER_PREFIX"; $parameter_prefix)
          + env_line("PUBLIC_IMAGE_BASENAME"; $public_image_basename)
          + env_line("IMAGE_REPOSITORY"; $image_repository)
          + env_line("GITHUB_ACTOR"; $github_actor)
          + env_line("GITHUB_TOKEN"; $github_token)
          + env_line("COMPOSE_B64"; $compose_b64)
          + env_line("ALLOY_B64"; $alloy_b64)
          + $script
        ]
      }
    '
}

deploy_on() {
  local id="$1" command_id status
  params_json > params.json
  command_id=$(aws ssm send-command --region "$AWS_REGION" \
    --document-name AWS-RunShellScript --instance-ids "$id" \
    --comment "${COMMENT_PREFIX} ${IMAGE_TAG}" --timeout-seconds 1800 \
    --parameters file://params.json --query 'Command.CommandId' --output text)
  rm -f params.json

  # 후보 pull, 상태 확인, 롤백까지 포함해 최대 30분.
  status=Pending
  for _ in $(seq 1 180); do
    status=$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" --instance-id "$id" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Success|Failed|Cancelled|TimedOut) break ;; esac
    sleep 10
  done
  aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" --instance-id "$id" \
    --query '{status:Status,stdout:StandardOutputContent,stderr:StandardErrorContent}' --output json \
    | jq -r '"--- \(.status)\n--- stdout ---\n\(.stdout)\n--- stderr ---\n\(.stderr)"'
  [ "$status" = "Success" ]
}

if [ -n "$ASG_NAME" ]; then
  already=$(aws autoscaling describe-auto-scaling-groups --region "$AWS_REGION" --auto-scaling-group-names "$ASG_NAME" \
    --query 'AutoScalingGroups[0].SuspendedProcesses[].ProcessName' --output text)
  for p in $PAUSE_PROCESSES; do
    case " $already " in *" $p "*) ;; *) PAUSED_BY_US="$PAUSED_BY_US $p" ;; esac
  done
  PAUSED_BY_US="${PAUSED_BY_US# }"
  if [ -n "$PAUSED_BY_US" ]; then
    # shellcheck disable=SC2086
    aws autoscaling suspend-processes --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --scaling-processes $PAUSED_BY_US
    log "ASG 처리 멈춤: $PAUSED_BY_US — 끝나면 다시 켠다"
  fi
fi

for id in $INSTANCES; do
  if [ -n "$ASG_NAME" ]; then
    log "$id ALB 대상에서 등록 해제 — 진행 중 요청을 마저 끝낸다"
    OUT_OF_TARGET="$id"
    aws elbv2 deregister-targets --region "$AWS_REGION" --target-group-arn "$TARGET_GROUP_ARN" --targets "Id=$id"
    for _ in $(seq 1 60); do [ "$(target_state "$id")" = "unused" ] && break; sleep 5; done
    [ "$(target_state "$id")" = "unused" ] || { echo "$id 가 대상에서 빠지지 않았습니다" >&2; exit 1; }
  fi

  log "$id 배포"
  deployed=true
  deploy_on "$id" || deployed=false

  if [ -n "$ASG_NAME" ]; then
    # 실패해도 다시 등록한다 — 스크립트가 직전 릴리스로 되돌려 둔 서버다.
    aws elbv2 register-targets --region "$AWS_REGION" --target-group-arn "$TARGET_GROUP_ARN" --targets "Id=$id"
    OUT_OF_TARGET=""
    wait_target_healthy "$id"
    log "$id healthy — slow start ${SLOW_START_SECONDS}초 기다림"
    sleep "$SLOW_START_SECONDS"
  fi

  [ "$deployed" = "true" ] || { echo "$id 배포 실패 — 나머지 서버는 옛 버전 그대로 둔다" >&2; exit 1; }
done
log "전 서버 배포 끝"
