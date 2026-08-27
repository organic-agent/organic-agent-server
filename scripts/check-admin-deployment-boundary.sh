#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
admin_config="$repo_root/wes-admin-api/src/main/resources/application.yml"
admin_compose="$repo_root/docker-compose.admin-api.prod.yml"
cd_workflow="$repo_root/.github/workflows/cd.yml"
public_deploy_script="$repo_root/scripts/deploy-public-ssm.sh"
admin_deploy_script="$repo_root/scripts/deploy-admin-api-ssm.sh"
workflow_run_size_check="$repo_root/scripts/check-workflow-run-size.rb"
admin_alloy="$repo_root/monitoring/config.admin.alloy"
alloy_egress_script="$repo_root/scripts/configure-admin-alloy-egress.sh"
alloy_egress_unit="$repo_root/deploy/wes-admin-alloy-egress.service"

grep -Fq 'aws-parameterstore:/wes/admin-api/prod/' "$admin_config"
grep -Fq 'aws-parameterstore:/wes/admin-api/local/' "$admin_config"
if grep -Fq 'aws-parameterstore:/wes/prod/' "$admin_config"; then
  echo "관리자 API가 공개 API Parameter Store prefix를 읽으면 안 됩니다." >&2
  exit 1
fi

grep -Fq 'secrets.AWS_ADMIN_API_DEPLOY_ROLE_ARN' "$cd_workflow"
if grep -Fq 'secrets.AWS_ADMIN_DEPLOY_ROLE_ARN' "$cd_workflow"; then
  echo "관리자 API 배포 역할 secret 이름이 Infra 출력 계약과 다릅니다." >&2
  exit 1
fi

bash -n "$public_deploy_script"
bash -n "$admin_deploy_script"
ruby "$workflow_run_size_check" "$cd_workflow"

[ "$(grep -Fc 'scripts/deploy-public-ssm.sh' "$cd_workflow")" -eq 2 ]
[ "$(grep -Fc 'scripts/deploy-admin-api-ssm.sh' "$cd_workflow")" -eq 2 ]
grep -Fq -- '--rawfile script scripts/deploy-public-ssm.sh' "$cd_workflow"
grep -Fq -- '--rawfile script scripts/deploy-admin-api-ssm.sh' "$cd_workflow"
[ "$(grep -Fc -- '--arg github_token "${{ secrets.GITHUB_TOKEN }}"' "$cd_workflow")" -eq 2 ]
[ "$(grep -Fc 'env_line("GITHUB_TOKEN"; $github_token)' "$cd_workflow")" -eq 2 ]
grep -Fq 'echo "$GITHUB_TOKEN"' "$public_deploy_script"
grep -Fq 'docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin' "$public_deploy_script"
grep -Fq 'echo "$GITHUB_TOKEN"' "$admin_deploy_script"
grep -Fq 'docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin' "$admin_deploy_script"
if grep -Fq '${{' "$public_deploy_script" || grep -Fq '${{' "$admin_deploy_script"; then
  echo "원격 SSM 스크립트에 GitHub expression이 남아 있습니다." >&2
  exit 1
fi

rendered_compose_json="$(
  OWNER_LOWERCASE=boundary-test IMAGE_TAG=deadbeef \
    LOKI_URL=http://loki.internal.example:3100/loki/api/v1/push \
    LOKI_HOST=loki.internal.example LOKI_PRIMARY_IP=10.0.0.8 \
    docker compose -f "$admin_compose" config --format json
)"

printf '%s\n' "$rendered_compose_json" | jq -e '
  .networks["wes-admin-internal"].external == true and
  .networks["wes-admin-runtime"].external == true and
  .networks["wes-admin-alloy-egress"].external == true and
  ((.services["wes-admin-api"].networks | keys | sort) == ["wes-admin-internal", "wes-admin-runtime"]) and
  ((.services["wes-admin-alloy"].networks | keys) == ["wes-admin-alloy-egress"]) and
  (.services["wes-admin-api"].ports[0].host_ip == "127.0.0.1") and
  (.services["wes-admin-alloy"].image == "grafana/alloy:v1.18.1@sha256:0f4434c92b3e6cdac38bb129b344e1790c246f7b6e2eaffcc16a5fa363240e33") and
  (.services["wes-admin-alloy"].platform == "linux/arm64") and
  (.services["wes-admin-alloy"].user == "473:473") and
  (.services["wes-admin-alloy"].dns == ["127.0.0.1"]) and
  (.services["wes-admin-alloy"].extra_hosts == ["loki.internal.example=10.0.0.8"]) and
  (.services["wes-admin-alloy"].entrypoint == ["/bin/sh", "-ec"]) and
  (.services["wes-admin-alloy"].read_only == true) and
  (.services["wes-admin-alloy"].cap_drop == ["ALL"]) and
  (.services["wes-admin-alloy"].security_opt == ["no-new-privileges:true"]) and
  (.services["wes-admin-alloy"].deploy.resources.limits.pids == 128) and
  ((.services["wes-admin-alloy"].command | join(" ")) | contains("--disable-reporting")) and
  ((.services["wes-admin-alloy"].command | join(" ")) | contains("/run/wes-alloy-egress/ready")) and
  (any(.services["wes-admin-alloy"].volumes[]; .target == "/var/log/spring" and .read_only == true)) and
  (any(.services["wes-admin-alloy"].volumes[]; .target == "/run/wes-alloy-egress" and .read_only == true))
' >/dev/null
grep -Fq 'service = "wes-admin-api"' "$admin_alloy"
grep -Fq '/wes/admin-api/prod/app.logging.loki-url' "$admin_deploy_script"
grep -Fq 'wes-admin-alloy' "$admin_deploy_script"
grep -Fq 'docker/setup-qemu-action@v3' "$cd_workflow"
grep -Fq 'INTERNAL_NETWORK_SUBNET=172.30.0.0/24' "$admin_deploy_script"
grep -Fq 'RUNTIME_NETWORK_SUBNET=172.30.1.0/24' "$admin_deploy_script"
grep -Fq 'ALLOY_EGRESS_NETWORK_SUBNET=172.30.2.0/24' "$admin_deploy_script"
grep -Fq 'configure-admin-alloy-egress' "$admin_deploy_script"
grep -Fq 'systemctl enable wes-admin-alloy-egress.service' "$admin_deploy_script"
grep -Fq 'Before=docker.service' "$alloy_egress_unit"
grep -Fq '169.254.169.254/32' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -A "$CHAIN_NAME" -j REJECT' "$alloy_egress_script"
grep -Fq 'while iptables -w 5 -C DOCKER-USER -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -D DOCKER-USER -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -I DOCKER-USER 1 -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'while iptables -w 5 -C INPUT -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -D INPUT -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -I INPUT 1 -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'iptables -w 5 -A "$HOST_CHAIN_NAME" -j REJECT' "$alloy_egress_script"
grep -Fq 'EXPECTED_FORWARD_JUMP="-A DOCKER-USER -s $ALLOY_EGRESS_SUBNET -j $CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'EXPECTED_HOST_JUMP="-A INPUT -s $ALLOY_EGRESS_SUBNET -j $HOST_CHAIN_NAME"' "$alloy_egress_script"
grep -Fq 'FORWARD_JUMP_COUNT="$(iptables -w 5 -S DOCKER-USER' "$alloy_egress_script"
grep -Fq 'HOST_JUMP_COUNT="$(iptables -w 5 -S INPUT' "$alloy_egress_script"
grep -Fq '[ "$FORWARD_JUMP_COUNT" -eq 1 ]' "$alloy_egress_script"
grep -Fq '[ "$HOST_JUMP_COUNT" -eq 1 ]' "$alloy_egress_script"
grep -Fq 'FIRST_FORWARD_RULE=$(iptables -w 5 -S DOCKER-USER' "$admin_deploy_script"
grep -Fq 'FIRST_HOST_RULE=$(iptables -w 5 -S INPUT' "$admin_deploy_script"
grep -Fq 'READY_FILE="$READY_DIRECTORY/ready"' "$alloy_egress_script"
grep -Fq 'LOKI_PRIMARY_IP_FILE="$READY_DIRECTORY/loki-primary-ip"' "$alloy_egress_script"
grep -Fq 'install -m 0444 /dev/null "$READY_FILE"' "$alloy_egress_script"
grep -Fq 'test -r /run/wes-alloy-egress/ready' "$admin_deploy_script"
grep -Fq 'RESTART_STOPPED_ALLOY=false' "$admin_deploy_script"
grep -Fq "'[\"127.0.0.1\"]'" "$admin_deploy_script"
grep -Fq 'rollback_admin_api_release()' "$admin_deploy_script"
grep -Fq 'recover_admin_alloy_state()' "$admin_deploy_script"
grep -Fq 'restore_admin_host_artifacts()' "$admin_deploy_script"
grep -Fq 'require_compose_capabilities()' "$admin_deploy_script"
grep -Fq 'docker compose version >/dev/null' "$admin_deploy_script"
grep -Fq -- "'--project-directory'" "$admin_deploy_script"
grep -Fq -- "'--format'" "$admin_deploy_script"
grep -Fq -- "'--pull'" "$admin_deploy_script"
grep -Fq 'trap on_admin_deploy_exit EXIT' "$admin_deploy_script"
grep -Fq 'CANDIDATE_DIR="$WORK_DIR/.admin-candidate"' "$admin_deploy_script"
grep -Fq 'bash -n "$CANDIDATE_DIR/configure-admin-alloy-egress.sh"' "$admin_deploy_script"
grep -Fq 'config --quiet' "$admin_deploy_script"
grep -Fq '"$CANDIDATE_DIR/configure-admin-alloy-egress.sh"' "$admin_deploy_script"
grep -Fq '.configure-admin-alloy-egress.next' "$admin_deploy_script"
grep -Fq 'mv -f "$WORK_DIR/.docker-compose.admin-api.prod.yml.next"' "$admin_deploy_script"
grep -Fq 'PREVIOUS_IMAGE_ID=$(docker inspect --format' "$admin_deploy_script"
grep -Fq 'previous-image-tag' "$admin_deploy_script"
grep -Fq 'install -m 0600 "$WORK_DIR/docker-compose.admin-api.prod.yml"' "$admin_deploy_script"
grep -Fq 'install -m 0600 "$WORK_DIR/config.admin.alloy"' "$admin_deploy_script"
grep -Fq 'up -d --force-recreate --pull never' "$admin_deploy_script"
grep -Fq 'ROLLED_BACK_IMAGE_ID=$(docker inspect --format' "$admin_deploy_script"
grep -Fq 'http://127.0.0.1:8081/actuator/health' "$admin_deploy_script"
grep -Fq -- '--connect-timeout 2 --max-time 5' "$admin_deploy_script"
grep -Fq '첫 배포여서 이전 wes-admin-api 컨테이너가 없습니다' "$admin_deploy_script"
grep -Fq 'PREVIOUS_EGRESS_SCRIPT_AVAILABLE=true' "$admin_deploy_script"
grep -Fq 'PREVIOUS_EGRESS_UNIT_AVAILABLE=true' "$admin_deploy_script"
grep -Fq 'PREVIOUS_ADMIN_COMPOSE_AVAILABLE=true' "$admin_deploy_script"
grep -Fq 'PREVIOUS_ADMIN_ALLOY_CONFIG_AVAILABLE=true' "$admin_deploy_script"
grep -Fq 'PREVIOUS_ALLOY_IMAGE=$(docker inspect --format' "$admin_deploy_script"
grep -Fq 'PREVIOUS_ALLOY_IMAGE_ID=$(docker inspect --format' "$admin_deploy_script"
grep -Fq 'rm -f -- /usr/local/sbin/configure-admin-alloy-egress' "$admin_deploy_script"
grep -Fq 'rm -f -- /etc/systemd/system/wes-admin-alloy-egress.service' "$admin_deploy_script"
grep -Fq 'PREVIOUS_ALLOY_RESTART_POLICY=$(docker inspect' "$admin_deploy_script"
grep -Fq 'docker compose --project-directory "$WORK_DIR"' "$admin_deploy_script"
grep -Fq 'up -d --force-recreate --pull never wes-admin-alloy' "$admin_deploy_script"
grep -Fq 'up -d --force-recreate --pull never wes-admin-api' "$admin_deploy_script"
grep -Fq '공개 API 이전 릴리스 롤백을 시작합니다' "$public_deploy_script"
grep -Fq 'PREVIOUS_IMAGE_ID=$(docker inspect --format' "$public_deploy_script"
grep -Fq 'http://127.0.0.1:8080/actuator/health' "$public_deploy_script"
grep -Fq 'trap on_public_deploy_exit EXIT' "$public_deploy_script"
grep -Fq 'rollback_public_release()' "$public_deploy_script"
grep -Fq 'PREVIOUS_LEGACY_IMAGE="ghcr.io/${OWNER_LOWERCASE}/wes-server:latest"' "$public_deploy_script"
grep -Fq '"$PREVIOUS_LEGACY_IMAGE")' "$public_deploy_script"
grep -Fq 'EXPECTED_PREVIOUS_SERVICE=wes-server' "$public_deploy_script"
grep -Fq 'up -d --force-recreate --pull never "$PREVIOUS_SERVICE"' "$public_deploy_script"
grep -Fq 'handoff_public_service()' "$public_deploy_script"
grep -Fq 'handoff_public_service "$PREVIOUS_SERVICE"' "$public_deploy_script"
grep -Fq 'handoff_public_service "$TARGET_SERVICE"' "$public_deploy_script"
grep -Fq 'if [ "$PREVIOUS_RELEASE_AVAILABLE" = "true" ] && [ "$PREVIOUS_SERVICE" = "wes-server" ]; then' "$public_deploy_script"
grep -Fq '레거시 로그 권한 이전 전에 wes-app 컨테이너가 남아 있습니다' "$public_deploy_script"
grep -Fq '[ -d "$WORK_DIR/logs" ] && [ ! -L "$WORK_DIR/logs" ]' "$public_deploy_script"
grep -Fq 'find "$WORK_DIR/logs" -xdev -type l -print -quit' "$public_deploy_script"
grep -Fq 'find "$WORK_DIR/logs" -xdev \( -type d -o -type f \)' "$public_deploy_script"
grep -Fq -- '-exec chown -h 10001:10001 {} +' "$public_deploy_script"
grep -Fq 'elif [ "$PREVIOUS_RELEASE_AVAILABLE" = "false" ]; then' "$public_deploy_script"
grep -Fq '첫 공개 API 로그 루트가 실제 디렉터리가 아닙니다' "$public_deploy_script"
grep -Fq 'FIRST_RELEASE_LOG_SYMLINK=$(find "$WORK_DIR/logs" -xdev -type l -print -quit)' "$public_deploy_script"
if grep -Fq 'mkdir -p "$WORK_DIR" "$WORK_DIR/logs"' "$public_deploy_script"; then
  echo "공개 API 로그 경로를 검증 전에 mkdir -p로 따라가면 안 됩니다." >&2
  exit 1
fi
grep -Fq -- '--timeout-seconds 1800' "$cd_workflow"
grep -Fq 'for i in $(seq 1 180); do' "$cd_workflow"
grep -Fq 'Loki URL must contain a host and no inline credentials' "$alloy_egress_script"

admin_alloy_recovery_block="$(awk '
  /^recover_admin_alloy_state\(\) \(/ { capture = 1 }
  capture { print }
  capture && /^\)$/ { exit }
' "$admin_deploy_script")"
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq -- \
  '-f "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml"'
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq \
  '/usr/local/sbin/configure-admin-alloy-egress'
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq \
  'previous Alloy compose does not satisfy the fail-closed boundary'
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq \
  'fail_closed_admin_alloy_recovery'
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq \
  "'{{.State.Restarting}}'"
printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq \
  'FAIL_CLOSED_POLICY'
if printf '%s\n' "$admin_alloy_recovery_block" | grep -Fq '$CANDIDATE_DIR'; then
  echo "관리자 Alloy rollback이 실패한 candidate artifact를 다시 사용합니다." >&2
  exit 1
fi

candidate_validation_line="$(grep -nF 'bash -n "$CANDIDATE_DIR/configure-admin-alloy-egress.sh"' "$admin_deploy_script" | head -n 1 | cut -d: -f1)"
compose_capability_gate_line="$(grep -nFx 'require_compose_capabilities' "$admin_deploy_script" | head -n 1 | cut -d: -f1)"
first_host_mutation_line="$(grep -nF 'mkdir -p "$WORK_DIR"' "$admin_deploy_script" | head -n 1 | cut -d: -f1)"
rollback_arm_line="$(grep -nF 'ROLLBACK_ARMED=true' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
live_swap_line="$(grep -nF 'mv -f "$WORK_DIR/.docker-compose.admin-api.prod.yml.next"' "$admin_deploy_script" | head -n 1 | cut -d: -f1)"
admin_candidate_pull_line="$(grep -nF 'docker-compose.admin-api.prod.yml" pull' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
admin_live_firewall_line="$(grep -nF '  /usr/local/sbin/configure-admin-alloy-egress' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
if [ "$compose_capability_gate_line" -ge "$first_host_mutation_line" ] || \
  [ "$candidate_validation_line" -ge "$admin_candidate_pull_line" ] || \
  [ "$admin_candidate_pull_line" -ge "$rollback_arm_line" ] || \
  [ "$rollback_arm_line" -ge "$live_swap_line" ] || \
  [ "$live_swap_line" -ge "$admin_live_firewall_line" ]; then
  echo "관리자 배포 candidate 검증, rollback arm, live swap 순서가 안전하지 않습니다." >&2
  exit 1
fi

public_validation_line="$(grep -nF 'docker-compose.prod.yml" config --quiet' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_arm_line="$(grep -nF 'ROLLBACK_ARMED=true' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_swap_line="$(grep -nF 'mv -f "$WORK_DIR/.docker-compose.prod.yml.next"' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_candidate_pull_line="$(grep -nF 'docker-compose.prod.yml" pull' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_target_handoff_line="$(grep -nF 'handoff_public_service "$TARGET_SERVICE"' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_target_up_line="$(grep -nF 'docker compose -f docker-compose.prod.yml up -d --force-recreate --pull never' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_legacy_log_guard_line="$(grep -nF 'if [ "$PREVIOUS_RELEASE_AVAILABLE" = "true" ] && [ "$PREVIOUS_SERVICE" = "wes-server" ]; then' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_log_writer_absent_line="$(grep -nF '레거시 로그 권한 이전 전에 wes-app 컨테이너가 남아 있습니다' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
public_log_handoff_line="$(grep -nF 'find "$WORK_DIR/logs" -xdev' "$public_deploy_script" | head -n 1 | cut -d: -f1)"
if [ "$public_validation_line" -ge "$public_candidate_pull_line" ] || \
  [ "$public_candidate_pull_line" -ge "$public_arm_line" ] || \
  [ "$public_arm_line" -ge "$public_swap_line" ] || \
  [ "$public_swap_line" -ge "$public_target_handoff_line" ] || \
  [ "$public_target_handoff_line" -ge "$public_legacy_log_guard_line" ] || \
  [ "$public_legacy_log_guard_line" -ge "$public_log_writer_absent_line" ] || \
  [ "$public_log_writer_absent_line" -ge "$public_log_handoff_line" ] || \
  [ "$public_log_handoff_line" -ge "$public_target_up_line" ]; then
  echo "공개 API candidate 검증, rollback arm, live swap 순서가 안전하지 않습니다." >&2
  exit 1
fi

admin_api_rollback_call_line="$(grep -nF 'rollback_admin_api_release' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
admin_alloy_recovery_call_line="$(grep -nF 'recover_admin_alloy_state' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
admin_host_restore_call_line="$(grep -nF 'restore_admin_host_artifacts' "$admin_deploy_script" | tail -n 1 | cut -d: -f1)"
if [ "$admin_api_rollback_call_line" -ge "$admin_host_restore_call_line" ] || \
  [ "$admin_host_restore_call_line" -ge "$admin_alloy_recovery_call_line" ]; then
  echo "관리자 API, host artifact, Alloy 경계 복구 순서가 안전하지 않습니다." >&2
  exit 1
fi

[ "$(grep -Fc -- '--timeout-seconds 1800' "$cd_workflow")" -eq 2 ]
[ "$(grep -Fc 'for i in $(seq 1 180); do' "$cd_workflow")" -eq 2 ]

echo "Admin deployment boundary checks passed"
