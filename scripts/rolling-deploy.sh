#!/usr/bin/env bash
# 공개 API를 앱 서버마다 한 대씩 바꾼다(#313 ADR-0001) — CD 러너에서 돈다. 서버 안의 일은 deploy-public-ssm.sh 가 한다.
#
#   서버 한 대씩:  (ASG 소속이면) standby 로 ALB 에서 뺌 → deploy-public-ssm.sh(SSM) → 복귀 → ALB healthy → slow start 만큼 기다림
#   한 대라도 실패하면 거기서 멈춘다 — 그 서버는 스크립트가 직전 릴리스로 되돌렸고, 나머지 서버는 옛 버전으로 계속 받는다.
#
# ASG 가 아닌 서버(옛 단일 인스턴스)면 standby 없이 그 자리에서 바꾼다 — 오늘과 같은 동작.
# ASG 는 min 2 라 standby 로 빼면 새 서버를 띄우려 든다 → 도는 동안 min 을 1 낮췄다가 끝나면(실패해도) 되돌린다.
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

ORIGINAL_MIN=""
TARGET_GROUP_ARN=""
# 이 스크립트가 멈춘 알람 처리. 이미 누가 멈춰 둔 것이면 끝날 때 다시 켜지 않는다.
SUSPENDED_ALARMS=false
if [ -n "$ASG_NAME" ]; then
  read -r ORIGINAL_MIN TARGET_GROUP_ARN < <(aws autoscaling describe-auto-scaling-groups --region "$AWS_REGION" \
    --auto-scaling-group-names "$ASG_NAME" --query 'AutoScalingGroups[0].[MinSize,TargetGroupARNs[0]]' --output text)
  log "ASG $ASG_NAME · min $ORIGINAL_MIN · 대상 그룹 $TARGET_GROUP_ARN"
fi

# 지금 standby 로 뺀 서버. 복귀까지 마치면 비운다.
IN_STANDBY=""

lifecycle_of() {
  aws autoscaling describe-auto-scaling-instances --region "$AWS_REGION" --instance-ids "$1" \
    --query 'AutoScalingInstances[0].LifecycleState' --output text
}

# 끝날 때(실패해도) 되돌린다. standby 에 남은 서버가 있으면 먼저 복귀시킨다 — 그 서버가 desired 를 1 줄여 둔 채면 min 을 원래대로
# 올리는 호출이 desired < min 으로 거절된다(PR #317 리뷰).
cleanup() {
  [ -n "$ASG_NAME" ] || return 0
  if [ -n "$IN_STANDBY" ] && [ "$(lifecycle_of "$IN_STANDBY" 2>/dev/null)" = "Standby" ]; then
    aws autoscaling exit-standby --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --instance-ids "$IN_STANDBY" >/dev/null || echo "$IN_STANDBY standby 복귀 실패 — 손으로 exit-standby" >&2
  fi
  if [ -n "$ORIGINAL_MIN" ]; then
    aws autoscaling update-auto-scaling-group --region "$AWS_REGION" \
      --auto-scaling-group-name "$ASG_NAME" --min-size "$ORIGINAL_MIN" || echo "ASG min 되돌리기 실패 — 손으로 $ORIGINAL_MIN 으로" >&2
  fi
  # min 을 먼저 되돌린 뒤에 켠다 — 반대 순서면 켜지는 순간 ALARM 인 줄이기가 min 1 아래로 desired 를 내릴 수 있다.
  if [ "$SUSPENDED_ALARMS" = "true" ]; then
    aws autoscaling resume-processes --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --scaling-processes AlarmNotification || echo "ASG 알람 처리 재개 실패 — 손으로 resume-processes AlarmNotification" >&2
  fi
}
trap cleanup EXIT

wait_lifecycle() {
  local id="$1" want="$2"
  for _ in $(seq 1 60); do
    [ "$(lifecycle_of "$id")" = "$want" ] && return 0
    sleep 5
  done
  echo "$id 가 $want 가 되지 않았습니다" >&2
  return 1
}

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

# CPU 목표 추적의 줄이기 알람(CPU < 35% × 15분)은 한가할 때 늘 ALARM 이다. min 을 낮추는 순간 그 알람이 desired 를 1 로 내려
# standby 가 거절됐다(dev 10-16 20:34, min-size=1 desired-size=1). 배포 동안 알람 처리를 멈춘다 — 그동안 늘리기도 멈춘다(≈ 서버당 1분 반).
if [ -n "$ASG_NAME" ]; then
  already=$(aws autoscaling describe-auto-scaling-groups --region "$AWS_REGION" --auto-scaling-group-names "$ASG_NAME" \
    --query "AutoScalingGroups[0].SuspendedProcesses[?ProcessName=='AlarmNotification'] | length(@)" --output text)
  if [ "$already" = "0" ]; then
    aws autoscaling suspend-processes --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --scaling-processes AlarmNotification
    SUSPENDED_ALARMS=true
    log "ASG 알람 처리(늘리기·줄이기) 멈춤 — 끝나면 다시 켠다"
  fi
fi

if [ -n "$ASG_NAME" ] && [ "$ORIGINAL_MIN" -gt 1 ]; then
  aws autoscaling update-auto-scaling-group --region "$AWS_REGION" \
    --auto-scaling-group-name "$ASG_NAME" --min-size $((ORIGINAL_MIN - 1))
fi

for id in $INSTANCES; do
  if [ -n "$ASG_NAME" ]; then
    log "$id standby — ALB 에서 빼고 진행 중 요청을 마저 끝낸다"
    IN_STANDBY="$id"
    aws autoscaling enter-standby --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --instance-ids "$id" --should-decrement-desired-capacity >/dev/null
    wait_lifecycle "$id" Standby
  fi

  log "$id 배포"
  deployed=true
  deploy_on "$id" || deployed=false

  if [ -n "$ASG_NAME" ]; then
    # 실패해도 복귀시킨다 — 스크립트가 직전 릴리스로 되돌려 둔 서버다.
    aws autoscaling exit-standby --region "$AWS_REGION" --auto-scaling-group-name "$ASG_NAME" \
      --instance-ids "$id" >/dev/null
    wait_lifecycle "$id" InService
    IN_STANDBY=""
    wait_target_healthy "$id"
    log "$id healthy — slow start ${SLOW_START_SECONDS}초 기다림"
    sleep "$SLOW_START_SECONDS"
  fi

  [ "$deployed" = "true" ] || { echo "$id 배포 실패 — 나머지 서버는 옛 버전 그대로 둔다" >&2; exit 1; }
done
log "전 서버 배포 끝"
