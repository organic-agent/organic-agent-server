#!/bin/sh

set -eu

# BackOffice 배포와 같은 잠금을 사용해 두 컨테이너 교체가 겹치지 않게 한다.
exec 9>/var/lock/wes-admin-deploy.lock
flock -w 600 9

require_compose_capabilities() {
  docker compose version >/dev/null 2>&1 || {
    echo "Docker Compose v2가 없어 관리자 API를 안전하게 배포할 수 없습니다" >&2
    exit 1
  }

  COMPOSE_HELP=$(LC_ALL=C docker compose --help 2>&1) || {
    echo "Docker Compose 전역 옵션을 확인할 수 없습니다" >&2
    exit 1
  }
  CONFIG_HELP=$(LC_ALL=C docker compose config --help 2>&1) || {
    echo "Docker Compose config 기능을 확인할 수 없습니다" >&2
    exit 1
  }
  UP_HELP=$(LC_ALL=C docker compose up --help 2>&1) || {
    echo "Docker Compose up 기능을 확인할 수 없습니다" >&2
    exit 1
  }
  printf '%s\n' "$COMPOSE_HELP" | grep -Fq -- '--project-directory' &&
    printf '%s\n' "$CONFIG_HELP" | grep -Fq -- '--format' &&
    printf '%s\n' "$UP_HELP" | grep -Fq -- '--pull' || {
      echo "Docker Compose가 관리자 API의 검증·불변 롤백 기능을 지원하지 않습니다" >&2
      exit 1
    }
}

# 호스트 상태나 파일을 변경하기 전에 v2 전용 검증·롤백 계약을 확인한다.
require_compose_capabilities

export OWNER_LOWERCASE="${OWNER_LOWERCASE}"
export IMAGE_TAG="${IMAGE_TAG}"
TARGET_IMAGE_TAG="$IMAGE_TAG"
WORK_DIR=/opt/wes-admin-api-prod
ROLLBACK_DIR="$WORK_DIR/.admin-rollback"
CANDIDATE_DIR="$WORK_DIR/.admin-candidate"
INTERNAL_NETWORK_NAME=wes-admin-internal
INTERNAL_NETWORK_SUBNET=172.30.0.0/24
RUNTIME_NETWORK_NAME=wes-admin-runtime
RUNTIME_NETWORK_SUBNET=172.30.1.0/24
ALLOY_EGRESS_NETWORK_NAME=wes-admin-alloy-egress
ALLOY_EGRESS_NETWORK_SUBNET=172.30.2.0/24
PREVIOUS_RELEASE_AVAILABLE=false
ROLLBACK_ARMED=false
REGISTRY_LOGGED_IN=false
PREVIOUS_IMAGE=
PREVIOUS_IMAGE_ID=
PREVIOUS_IMAGE_TAG=
HOST_STATE_CAPTURED=false
PREVIOUS_EGRESS_SCRIPT_AVAILABLE=false
PREVIOUS_EGRESS_UNIT_AVAILABLE=false
PREVIOUS_EGRESS_UNIT_ENABLED=false
PREVIOUS_ADMIN_COMPOSE_AVAILABLE=false
PREVIOUS_ADMIN_ALLOY_CONFIG_AVAILABLE=false
PREVIOUS_ALLOY_EXISTS=false
PREVIOUS_ALLOY_WAS_ACTIVE=false
PREVIOUS_ALLOY_RESTART_POLICY=no
PREVIOUS_ALLOY_IMAGE=
PREVIOUS_ALLOY_IMAGE_ID=
# 관리자 API 이미지 한 벌(압축본 + 풀린 층)이 들어가고도 남는 여유.
MIN_FREE_DISK_KB=2097152

# 같은 저장소의 태그 이미지는 인자로 받은 것만 남긴다.
# `docker image prune`은 태그 없는 이미지만 지워 커밋마다 받은 이미지가 디스크를 채운다.
remove_stale_images() (
  IMAGE_REPOSITORY="$1"
  shift
  docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGE_REPOSITORY" |
    while IFS= read -r stale_image; do
      case "$stale_image" in
        *:"<none>") continue ;;
      esac
      case " $* " in
        *" $stale_image "*) continue ;;
      esac
      docker rmi "$stale_image" >/dev/null ||
        echo "옛 관리자 API 이미지를 지우지 못했습니다: $stale_image" >&2
    done
)

# 이미지를 풀다가 디스크가 차면 SSM 작업자까지 죽어 원인이 로그에 남지 않는다.
require_free_disk() (
  set -eu

  for docker_data_dir in "$(docker info --format '{{.DockerRootDir}}')" /var/lib/containerd; do
    [ -d "$docker_data_dir" ] || continue
    FREE_DISK_KB=$(df -Pk "$docker_data_dir" | awk 'NR == 2 { print $4 }')
    [ "$FREE_DISK_KB" -ge "$MIN_FREE_DISK_KB" ] || {
      echo "디스크 여유가 부족해 관리자 API 이미지를 받지 않습니다: $docker_data_dir 남은 $((FREE_DISK_KB / 1024))MB, 필요 $((MIN_FREE_DISK_KB / 1024))MB" >&2
      return 1
    }
  done
)

rollback_admin_api_release() (
  set -eu

  echo "관리자 API 이전 릴리스 롤백을 시작합니다" >&2
  install -m 0644 "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" \
    "$WORK_DIR/.docker-compose.admin-api.prod.yml.rollback"
  install -m 0644 "$ROLLBACK_DIR/config.admin.alloy" \
    "$WORK_DIR/.config.admin.alloy.rollback"
  mv -f "$WORK_DIR/.docker-compose.admin-api.prod.yml.rollback" \
    "$WORK_DIR/docker-compose.admin-api.prod.yml"
  mv -f "$WORK_DIR/.config.admin.alloy.rollback" "$WORK_DIR/config.admin.alloy"
  cmp -s "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" \
    "$WORK_DIR/docker-compose.admin-api.prod.yml"
  cmp -s "$ROLLBACK_DIR/config.admin.alloy" "$WORK_DIR/config.admin.alloy"

  # The previous container keeps the image layers referenced until replacement.
  # Re-attach its immutable image ID to the recorded tag and never pull during rollback.
  docker image inspect "$PREVIOUS_IMAGE_ID" >/dev/null
  docker image tag "$PREVIOUS_IMAGE_ID" "$PREVIOUS_IMAGE"
  export IMAGE_TAG="$PREVIOUS_IMAGE_TAG"

  cd "$WORK_DIR"
  docker compose -f docker-compose.admin-api.prod.yml \
    up -d --force-recreate --pull never wes-admin-api

  ROLLED_BACK_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-admin-api)
  ROLLED_BACK_IMAGE_ID=$(docker inspect --format '{{.Image}}' wes-admin-api)
  [ "$ROLLED_BACK_IMAGE" = "$PREVIOUS_IMAGE" ] || {
    echo "롤백 이미지 불일치: $ROLLED_BACK_IMAGE (expected $PREVIOUS_IMAGE)" >&2
    return 1
  }
  [ "$ROLLED_BACK_IMAGE_ID" = "$PREVIOUS_IMAGE_ID" ] || {
    echo "롤백 이미지 ID 불일치: $ROLLED_BACK_IMAGE_ID (expected $PREVIOUS_IMAGE_ID)" >&2
    return 1
  }

  for rollback_attempt in $(seq 1 30); do
    ROLLBACK_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:8081/actuator/health" || true)
    if [ "$ROLLBACK_HTTP_CODE" = "200" ]; then
      echo "관리자 API 이전 릴리스 복구 완료 (${rollback_attempt}번째 시도)" >&2
      return 0
    fi
    sleep 5
  done

  echo "이전 관리자 API 이미지로 복구했지만 8081 health 검증에 실패했습니다" >&2
  docker logs --tail 100 wes-admin-api >&2 || true
  return 1
)

recover_admin_alloy_state() (
  set -eu

  [ "$HOST_STATE_CAPTURED" = "true" ] || return 0
  CURRENT_ALLOY_EXISTS=false
  CURRENT_ALLOY_ACTIVE=false
  CURRENT_ALLOY_RESTART_POLICY=no
  if docker inspect wes-admin-alloy >/dev/null 2>&1; then
    CURRENT_ALLOY_EXISTS=true
    CURRENT_ALLOY_RESTART_POLICY=$(docker inspect \
      --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)
    if [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "true" ] || \
      [ "$(docker inspect --format '{{.State.Restarting}}' wes-admin-alloy)" = "true" ]; then
      CURRENT_ALLOY_ACTIVE=true
    fi
  fi

  # Before any live swap or policy refresh, an unchanged runtime is already
  # the exact previous state. In particular, never restart a legacy container
  # whose Docker DNS boundary has not yet been upgraded.
  if [ "$ROLLBACK_ARMED" = "false" ] && \
    [ "$CURRENT_ALLOY_EXISTS" = "$PREVIOUS_ALLOY_EXISTS" ] && \
    [ "$CURRENT_ALLOY_ACTIVE" = "$PREVIOUS_ALLOY_WAS_ACTIVE" ] && \
    [ "$CURRENT_ALLOY_RESTART_POLICY" = "$PREVIOUS_ALLOY_RESTART_POLICY" ]; then
    return 0
  fi

  if [ "$PREVIOUS_ALLOY_EXISTS" = "false" ]; then
    if [ "$CURRENT_ALLOY_EXISTS" = "true" ]; then
      if ! docker update --restart=no wes-admin-alloy >/dev/null; then
        docker rm -f wes-admin-alloy >/dev/null 2>&1 || true
        echo "이전에는 없던 Alloy의 restart policy를 해제하지 못했습니다" >&2
        return 1
      fi
      if ! docker stop --time 30 wes-admin-alloy >/dev/null; then
        docker rm -f wes-admin-alloy >/dev/null 2>&1 || true
        echo "이전에는 없던 Alloy를 중지하지 못했습니다" >&2
        return 1
      fi
      [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "false" ]
      [ "$(docker inspect --format '{{.State.Restarting}}' wes-admin-alloy)" = "false" ]
      [ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)" = "no" ]
    fi
    return 0
  fi

  fail_closed_admin_alloy_recovery() {
    RECOVERY_STATUS=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$RECOVERY_STATUS" -ne 0 ]; then
      if docker inspect wes-admin-alloy >/dev/null 2>&1; then
        FAIL_CLOSED_UPDATE_OK=true
        FAIL_CLOSED_STOP_OK=true
        docker update --restart=no wes-admin-alloy >/dev/null 2>&1 || \
          FAIL_CLOSED_UPDATE_OK=false
        docker stop --time 30 wes-admin-alloy >/dev/null 2>&1 || \
          FAIL_CLOSED_STOP_OK=false
        if [ "$FAIL_CLOSED_UPDATE_OK" = "false" ] || \
          [ "$FAIL_CLOSED_STOP_OK" = "false" ]; then
          docker rm -f wes-admin-alloy >/dev/null 2>&1 || true
        fi
        if docker inspect wes-admin-alloy >/dev/null 2>&1; then
          FAIL_CLOSED_RUNNING=$(docker inspect \
            --format '{{.State.Running}}' wes-admin-alloy)
          FAIL_CLOSED_RESTARTING=$(docker inspect \
            --format '{{.State.Restarting}}' wes-admin-alloy)
          FAIL_CLOSED_POLICY=$(docker inspect \
            --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)
          if [ "$FAIL_CLOSED_RUNNING" != "false" ] || \
            [ "$FAIL_CLOSED_RESTARTING" != "false" ] || \
            [ "$FAIL_CLOSED_POLICY" != "no" ]; then
            echo "CRITICAL: Alloy collector를 fail-closed 상태로 만들지 못했습니다" >&2
          fi
        fi
      fi
      echo "이전 Alloy 경계를 검증·복구하지 못해 collector를 fail-closed 상태로 유지합니다" >&2
    fi
    exit "$RECOVERY_STATUS"
  }
  trap fail_closed_admin_alloy_recovery EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  # Never retry the failed candidate here. Only the immutable snapshots
  # captured before deployment may be used to recover the prior collector.
  [ "$PREVIOUS_EGRESS_SCRIPT_AVAILABLE" = "true" ] && \
    [ "$PREVIOUS_EGRESS_UNIT_AVAILABLE" = "true" ] && \
    [ "$PREVIOUS_ADMIN_COMPOSE_AVAILABLE" = "true" ] && \
    [ "$PREVIOUS_ADMIN_ALLOY_CONFIG_AVAILABLE" = "true" ] && \
    [ -n "${LOKI_URL:-}" ] && \
    [ -n "$PREVIOUS_ALLOY_IMAGE" ] && \
    [ -n "$PREVIOUS_ALLOY_IMAGE_ID" ] || {
      echo "이전 Alloy script/unit/compose/image snapshot이 불완전해 재시작하지 않습니다" >&2
      return 1
    }
  cmp -s "$ROLLBACK_DIR/configure-admin-alloy-egress" \
    /usr/local/sbin/configure-admin-alloy-egress
  cmp -s "$ROLLBACK_DIR/wes-admin-alloy-egress.service" \
    /etc/systemd/system/wes-admin-alloy-egress.service
  bash -n /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'RESTART_STOPPED_ALLOY="${RESTART_STOPPED_ALLOY:-true}"' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'rm -f -- "$READY_FILE"' /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'iptables -w 5 -I DOCKER-USER 1 -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'iptables -w 5 -I INPUT 1 -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'iptables -w 5 -A "$CHAIN_NAME" -j REJECT' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'iptables -w 5 -A "$HOST_CHAIN_NAME" -j REJECT' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fq 'install -m 0444 /dev/null "$READY_FILE"' \
    /usr/local/sbin/configure-admin-alloy-egress
  grep -Fxq 'Before=docker.service' /etc/systemd/system/wes-admin-alloy-egress.service
  grep -Fxq 'ExecStart=/usr/local/sbin/configure-admin-alloy-egress' \
    /etc/systemd/system/wes-admin-alloy-egress.service
  if [ "$PREVIOUS_ALLOY_RESTART_POLICY" != "no" ]; then
    [ "$PREVIOUS_EGRESS_UNIT_ENABLED" = "true" ] && \
      systemctl is-enabled --quiet wes-admin-alloy-egress.service
  fi

  install -m 0644 "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" \
    "$WORK_DIR/.docker-compose.admin-api.prod.yml.alloy-rollback"
  install -m 0644 "$ROLLBACK_DIR/config.admin.alloy" \
    "$WORK_DIR/.config.admin.alloy.alloy-rollback"
  mv -f "$WORK_DIR/.docker-compose.admin-api.prod.yml.alloy-rollback" \
    "$WORK_DIR/docker-compose.admin-api.prod.yml"
  mv -f "$WORK_DIR/.config.admin.alloy.alloy-rollback" \
    "$WORK_DIR/config.admin.alloy"
  cmp -s "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" \
    "$WORK_DIR/docker-compose.admin-api.prod.yml"
  cmp -s "$ROLLBACK_DIR/config.admin.alloy" "$WORK_DIR/config.admin.alloy"

  export IMAGE_TAG="${PREVIOUS_IMAGE_TAG:-$TARGET_IMAGE_TAG}"
  docker compose --project-directory "$WORK_DIR" \
    -f "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" config --format json \
    | PREVIOUS_ALLOY_IMAGE="$PREVIOUS_ALLOY_IMAGE" \
      EXPECTED_LOKI_HOST="$LOKI_HOST" \
      EXPECTED_LOKI_IP="$LOKI_PRIMARY_IP" \
      EXPECTED_ALLOY_NETWORK="$ALLOY_EGRESS_NETWORK_NAME" \
      python3 -c '
import json
import os
import sys

config = json.load(sys.stdin)
service = config.get("services", {}).get("wes-admin-alloy", {})
expected_host = os.environ["EXPECTED_LOKI_HOST"]
expected_ip = os.environ["EXPECTED_LOKI_IP"]
extra_hosts = service.get("extra_hosts") or []
if isinstance(extra_hosts, dict):
    host_pin_ok = extra_hosts == {expected_host: expected_ip}
else:
    host_pin_ok = len(extra_hosts) == 1 and extra_hosts[0] in {
        f"{expected_host}:{expected_ip}",
        f"{expected_host}={expected_ip}",
    }
networks = service.get("networks") or {}
network_names = set(networks if isinstance(networks, dict) else networks)
command = json.dumps(service.get("command", ""))
checks = [
    service.get("image") == os.environ["PREVIOUS_ALLOY_IMAGE"],
    service.get("dns") == ["127.0.0.1"],
    host_pin_ok,
    network_names == {os.environ["EXPECTED_ALLOY_NETWORK"]},
    service.get("read_only") is True,
    service.get("user") == "473:473",
    "ALL" in (service.get("cap_drop") or []),
    "/run/wes-alloy-egress/ready" in command,
]
if not all(checks):
    raise SystemExit("previous Alloy compose does not satisfy the fail-closed boundary")
'

  docker image inspect "$PREVIOUS_ALLOY_IMAGE_ID" >/dev/null
  case "$PREVIOUS_ALLOY_IMAGE" in
    *@sha256:*) ;;
    *) docker image tag "$PREVIOUS_ALLOY_IMAGE_ID" "$PREVIOUS_ALLOY_IMAGE" ;;
  esac

  ALLOY_EGRESS_SUBNET="$ALLOY_EGRESS_NETWORK_SUBNET" \
    AWS_REGION="${AWS_REGION}" \
    LOKI_URL="$LOKI_URL" \
    RESTART_STOPPED_ALLOY=false \
    /usr/local/sbin/configure-admin-alloy-egress
  test -r /run/wes-alloy-egress/ready
  export LOKI_HOST=$(cat /run/wes-alloy-egress/loki-host)
  export LOKI_PRIMARY_IP=$(cat /run/wes-alloy-egress/loki-primary-ip)
  export LOKI_PORT=$(cat /run/wes-alloy-egress/loki-port)

  docker compose --project-directory "$WORK_DIR" \
    -f "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml" \
    up -d --force-recreate --pull never wes-admin-alloy
  [ "$(docker inspect --format '{{.Config.Image}}' wes-admin-alloy)" = \
    "$PREVIOUS_ALLOY_IMAGE" ]
  [ "$(docker inspect --format '{{.Image}}' wes-admin-alloy)" = \
    "$PREVIOUS_ALLOY_IMAGE_ID" ]
  [ "$(docker inspect --format '{{json .HostConfig.Dns}}' wes-admin-alloy)" = \
    '["127.0.0.1"]' ]
  [ "$(docker inspect --format '{{json .HostConfig.ExtraHosts}}' wes-admin-alloy)" = \
    "[\"$LOKI_HOST:$LOKI_PRIMARY_IP\"]" ]
  [ "$(docker inspect --format '{{.HostConfig.NetworkMode}}' wes-admin-alloy)" = \
    "$ALLOY_EGRESS_NETWORK_NAME" ]

  docker update --restart=no wes-admin-alloy >/dev/null
  if [ "$PREVIOUS_ALLOY_WAS_ACTIVE" = "true" ]; then
    docker start wes-admin-alloy >/dev/null
    [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "true" ]
  else
    docker stop --time 30 wes-admin-alloy >/dev/null || true
    [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "false" ]
  fi
  docker update --restart="$PREVIOUS_ALLOY_RESTART_POLICY" wes-admin-alloy >/dev/null
  [ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)" = \
    "$PREVIOUS_ALLOY_RESTART_POLICY" ]
  trap - EXIT HUP INT TERM
)

restore_admin_host_artifacts() (
  set -eu
  [ "$HOST_STATE_CAPTURED" = "true" ] || return 0

  if [ "$PREVIOUS_EGRESS_SCRIPT_AVAILABLE" = "true" ]; then
    install -m 0755 "$ROLLBACK_DIR/configure-admin-alloy-egress" \
      /usr/local/sbin/.configure-admin-alloy-egress.rollback
    mv -f /usr/local/sbin/.configure-admin-alloy-egress.rollback \
      /usr/local/sbin/configure-admin-alloy-egress
  else
    rm -f -- /usr/local/sbin/configure-admin-alloy-egress \
      /usr/local/sbin/.configure-admin-alloy-egress.next
  fi
  if [ "$PREVIOUS_EGRESS_UNIT_AVAILABLE" = "true" ]; then
    install -m 0644 "$ROLLBACK_DIR/wes-admin-alloy-egress.service" \
      /etc/systemd/system/.wes-admin-alloy-egress.service.rollback
    mv -f /etc/systemd/system/.wes-admin-alloy-egress.service.rollback \
      /etc/systemd/system/wes-admin-alloy-egress.service
    systemctl daemon-reload
    if [ "$PREVIOUS_EGRESS_UNIT_ENABLED" = "true" ]; then
      systemctl enable wes-admin-alloy-egress.service >/dev/null
    else
      systemctl disable wes-admin-alloy-egress.service >/dev/null
    fi
  else
    systemctl disable wes-admin-alloy-egress.service >/dev/null 2>&1 || true
    rm -f -- /etc/systemd/system/wes-admin-alloy-egress.service \
      /etc/systemd/system/.wes-admin-alloy-egress.service.next
    systemctl daemon-reload
  fi
)

on_admin_deploy_exit() {
  ORIGINAL_STATUS=$?
  trap - EXIT HUP INT TERM
  set +e

  if [ "$ORIGINAL_STATUS" -ne 0 ]; then
    if [ "$ROLLBACK_ARMED" = "true" ] && [ "$PREVIOUS_RELEASE_AVAILABLE" = "true" ]; then
      rollback_admin_api_release
      [ "$?" -eq 0 ] || echo "관리자 API 롤백도 실패했습니다; 수동 복구가 필요합니다" >&2
    elif [ "$ROLLBACK_ARMED" = "true" ]; then
      echo "첫 배포여서 이전 wes-admin-api 컨테이너가 없습니다; 롤백 없이 실패 상태를 유지합니다" >&2
    else
      echo "배포 변경 전 실패하여 기존 관리자 API 릴리스는 그대로 유지됩니다" >&2
    fi

    restore_admin_host_artifacts
    [ "$?" -eq 0 ] || echo "이전 Alloy host script/unit 복원에 실패했습니다" >&2
    recover_admin_alloy_state
    [ "$?" -eq 0 ] || echo "Alloy의 이전 실행 상태를 안전하게 복구하지 못했습니다" >&2
  fi

  if [ "$REGISTRY_LOGGED_IN" = "true" ]; then
    docker logout ghcr.io >/dev/null 2>&1 || true
  fi
  exit "$ORIGINAL_STATUS"
}

trap on_admin_deploy_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$WORK_DIR" "$WORK_DIR/admin-api-logs" \
  "$WORK_DIR/admin-alloy-data" "$ROLLBACK_DIR"
install -d -m 0700 "$CANDIDATE_DIR"
chown 10001:10001 "$WORK_DIR/admin-api-logs"
chown 473:473 "$WORK_DIR/admin-alloy-data"

if [ -f /usr/local/sbin/configure-admin-alloy-egress ]; then
  install -m 0600 /usr/local/sbin/configure-admin-alloy-egress \
    "$ROLLBACK_DIR/configure-admin-alloy-egress"
  PREVIOUS_EGRESS_SCRIPT_AVAILABLE=true
fi
if [ -f /etc/systemd/system/wes-admin-alloy-egress.service ]; then
  install -m 0600 /etc/systemd/system/wes-admin-alloy-egress.service \
    "$ROLLBACK_DIR/wes-admin-alloy-egress.service"
  PREVIOUS_EGRESS_UNIT_AVAILABLE=true
  if systemctl is-enabled --quiet wes-admin-alloy-egress.service; then
    PREVIOUS_EGRESS_UNIT_ENABLED=true
  fi
fi
if [ -f "$WORK_DIR/docker-compose.admin-api.prod.yml" ]; then
  install -m 0600 "$WORK_DIR/docker-compose.admin-api.prod.yml" \
    "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml"
  PREVIOUS_ADMIN_COMPOSE_AVAILABLE=true
fi
if [ -f "$WORK_DIR/config.admin.alloy" ]; then
  install -m 0600 "$WORK_DIR/config.admin.alloy" \
    "$ROLLBACK_DIR/config.admin.alloy"
  PREVIOUS_ADMIN_ALLOY_CONFIG_AVAILABLE=true
fi
if docker inspect wes-admin-alloy >/dev/null 2>&1; then
  PREVIOUS_ALLOY_EXISTS=true
  PREVIOUS_ALLOY_RESTART_POLICY=$(docker inspect \
    --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)
  PREVIOUS_ALLOY_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-admin-alloy)
  PREVIOUS_ALLOY_IMAGE_ID=$(docker inspect --format '{{.Image}}' wes-admin-alloy)
  docker image inspect "$PREVIOUS_ALLOY_IMAGE_ID" >/dev/null
  if [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "true" ] || \
    [ "$(docker inspect --format '{{.State.Restarting}}' wes-admin-alloy)" = "true" ]; then
    PREVIOUS_ALLOY_WAS_ACTIVE=true
  fi
fi
HOST_STATE_CAPTURED=true

if docker inspect wes-admin-api >/dev/null 2>&1; then
  [ "$PREVIOUS_ADMIN_COMPOSE_AVAILABLE" = "true" ] && \
    [ "$PREVIOUS_ADMIN_ALLOY_CONFIG_AVAILABLE" = "true" ] || {
      echo "기존 관리자 API는 있지만 이전 compose/Alloy 설정이 없어 안전하게 배포할 수 없습니다" >&2
      exit 1
    }

  PREVIOUS_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-admin-api)
  PREVIOUS_IMAGE_ID=$(docker inspect --format '{{.Image}}' wes-admin-api)
  PREVIOUS_IMAGE_PREFIX="ghcr.io/${OWNER_LOWERCASE}/${ADMIN_IMAGE_BASENAME}:"
  case "$PREVIOUS_IMAGE" in
    "$PREVIOUS_IMAGE_PREFIX"*)
      PREVIOUS_IMAGE_TAG=${PREVIOUS_IMAGE#"$PREVIOUS_IMAGE_PREFIX"}
      ;;
    *)
      echo "기존 관리자 API 이미지가 예상 저장소에 속하지 않습니다: $PREVIOUS_IMAGE" >&2
      exit 1
      ;;
  esac
  [ -n "$PREVIOUS_IMAGE_TAG" ] || {
    echo "기존 관리자 API 이미지 태그를 확인할 수 없습니다" >&2
    exit 1
  }
  docker image inspect "$PREVIOUS_IMAGE_ID" >/dev/null

  PREVIOUS_HEALTHY=false
  for preflight_attempt in $(seq 1 5); do
    PREFLIGHT_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:8081/actuator/health" || true)
    if [ "$PREFLIGHT_HTTP_CODE" = "200" ]; then
      PREVIOUS_HEALTHY=true
      break
    fi
    sleep 2
  done
  [ "$PREVIOUS_HEALTHY" = "true" ] || {
    echo "기존 관리자 API가 healthy하지 않아 검증 가능한 롤백 기준점이 없습니다" >&2
    exit 1
  }

  install -m 0600 "$WORK_DIR/docker-compose.admin-api.prod.yml" \
    "$ROLLBACK_DIR/docker-compose.admin-api.prod.yml"
  install -m 0600 "$WORK_DIR/config.admin.alloy" \
    "$ROLLBACK_DIR/config.admin.alloy"
  printf '%s\n' "$PREVIOUS_IMAGE" > "$ROLLBACK_DIR/previous-image"
  printf '%s\n' "$PREVIOUS_IMAGE_ID" > "$ROLLBACK_DIR/previous-image-id"
  printf '%s\n' "$PREVIOUS_IMAGE_TAG" > "$ROLLBACK_DIR/previous-image-tag"
  chmod 0600 "$ROLLBACK_DIR/previous-image" \
    "$ROLLBACK_DIR/previous-image-id" "$ROLLBACK_DIR/previous-image-tag"
  PREVIOUS_RELEASE_AVAILABLE=true
  echo "이전 관리자 API 릴리스 $PREVIOUS_IMAGE를 롤백용으로 보존했습니다"
else
  echo "첫 배포: 이전 wes-admin-api 컨테이너가 없어 실패 시 롤백할 릴리스가 없습니다" >&2
fi

ensure_network() {
  NETWORK_NAME="$1"
  NETWORK_SUBNET="$2"
  NETWORK_INTERNAL="$3"

  if docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    ACTUAL_INTERNAL=$(docker network inspect --format '{{.Internal}}' "$NETWORK_NAME")
    ACTUAL_SUBNET=$(docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}' "$NETWORK_NAME")
    [ "$ACTUAL_INTERNAL" = "$NETWORK_INTERNAL" ] && [ "$ACTUAL_SUBNET" = "$NETWORK_SUBNET" ] || {
      echo "$NETWORK_NAME 설정 불일치 (internal=$ACTUAL_INTERNAL, subnet=$ACTUAL_SUBNET)" >&2
      exit 1
    }
    return
  fi

  if [ "$NETWORK_INTERNAL" = "true" ]; then
    docker network create --driver bridge --internal \
      --subnet "$NETWORK_SUBNET" "$NETWORK_NAME" >/dev/null
  else
    docker network create --driver bridge \
      --subnet "$NETWORK_SUBNET" "$NETWORK_NAME" >/dev/null
  fi
}

# BackOffice 통신, 관리자 API runtime, Alloy 송신 경로를 서로 다른 대역으로 고정한다.
ensure_network "$INTERNAL_NETWORK_NAME" "$INTERNAL_NETWORK_SUBNET" true
ensure_network "$RUNTIME_NETWORK_NAME" "$RUNTIME_NETWORK_SUBNET" false
ensure_network "$ALLOY_EGRESS_NETWORK_NAME" "$ALLOY_EGRESS_NETWORK_SUBNET" false

export LOKI_URL=$(aws ssm get-parameter --region ${AWS_REGION} \
  --name /wes/admin-api/prod/app.logging.loki-url \
  --query Parameter.Value --output text)
[ -n "$LOKI_URL" ] || {
  echo "관리자 Loki 주소가 없습니다" >&2
  exit 1
}
export LOKI_HOST=$(python3 -c '
from urllib.parse import urlsplit
import sys
parsed = urlsplit(sys.argv[1])
if parsed.scheme not in {"http", "https"} or not parsed.hostname:
    raise SystemExit("invalid Loki URL")
print(parsed.hostname)
' "$LOKI_URL")
export LOKI_PRIMARY_IP=$(getent ahostsv4 "$LOKI_HOST" | awk '{print $1}' | sort -u | head -n 1)
[ -n "$LOKI_HOST" ] && [ -n "$LOKI_PRIMARY_IP" ] || {
  echo "Loki host/IP를 사전 확인하지 못했습니다" >&2
  exit 1
}

# Decode and validate every candidate while all live files and containers are
# still untouched. The live-path .next files make each subsequent rename atomic.
echo "${COMPOSE_B64}" | base64 -d > "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml"
echo "${ALLOY_B64}" | base64 -d > "$CANDIDATE_DIR/config.admin.alloy"
echo "${ALLOY_EGRESS_B64}" | base64 -d > "$CANDIDATE_DIR/configure-admin-alloy-egress.sh"
echo "${ALLOY_EGRESS_UNIT_B64}" | base64 -d > "$CANDIDATE_DIR/wes-admin-alloy-egress.service"
chmod 0600 "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml" \
  "$CANDIDATE_DIR/config.admin.alloy" "$CANDIDATE_DIR/wes-admin-alloy-egress.service"
chmod 0700 "$CANDIDATE_DIR/configure-admin-alloy-egress.sh"
[ -s "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml" ] && \
  [ -s "$CANDIDATE_DIR/config.admin.alloy" ] && \
  [ -s "$CANDIDATE_DIR/configure-admin-alloy-egress.sh" ] && \
  [ -s "$CANDIDATE_DIR/wes-admin-alloy-egress.service" ]
bash -n "$CANDIDATE_DIR/configure-admin-alloy-egress.sh"
docker compose --project-directory "$WORK_DIR" \
  -f "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml" config --quiet
grep -Fq 'ExecStart=/usr/local/sbin/configure-admin-alloy-egress' \
  "$CANDIDATE_DIR/wes-admin-alloy-egress.service"

# 롤백은 지금 도는 이미지 하나만 쓴다. 그 밖의 옛 태그를 받기 전에 지워 자리를 만든다.
TARGET_IMAGE="ghcr.io/${OWNER_LOWERCASE}/${ADMIN_IMAGE_BASENAME}:$TARGET_IMAGE_TAG"
remove_stale_images "ghcr.io/${OWNER_LOWERCASE}/${ADMIN_IMAGE_BASENAME}" \
  "$TARGET_IMAGE" "$PREVIOUS_IMAGE"
require_free_disk

echo "$GITHUB_TOKEN" \
  | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
REGISTRY_LOGGED_IN=true
docker compose --project-directory "$WORK_DIR" \
  -f "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml" pull

install -m 0644 "$CANDIDATE_DIR/docker-compose.admin-api.prod.yml" \
  "$WORK_DIR/.docker-compose.admin-api.prod.yml.next"
install -m 0644 "$CANDIDATE_DIR/config.admin.alloy" \
  "$WORK_DIR/.config.admin.alloy.next"
install -m 0755 "$CANDIDATE_DIR/configure-admin-alloy-egress.sh" \
  /usr/local/sbin/.configure-admin-alloy-egress.next
install -m 0644 "$CANDIDATE_DIR/wes-admin-alloy-egress.service" \
  /etc/systemd/system/.wes-admin-alloy-egress.service.next

# From the first atomic live-file swap onward, every failure must either
# restore the captured release or retain an explicit failed-first-deploy state.
ROLLBACK_ARMED=true
mv -f "$WORK_DIR/.docker-compose.admin-api.prod.yml.next" \
  "$WORK_DIR/docker-compose.admin-api.prod.yml"
mv -f "$WORK_DIR/.config.admin.alloy.next" "$WORK_DIR/config.admin.alloy"
mv -f /usr/local/sbin/.configure-admin-alloy-egress.next \
  /usr/local/sbin/configure-admin-alloy-egress
mv -f /etc/systemd/system/.wes-admin-alloy-egress.service.next \
  /etc/systemd/system/wes-admin-alloy-egress.service
systemctl daemon-reload
systemctl enable wes-admin-alloy-egress.service >/dev/null

# 전용 대역은 Loki의 현재 IPv4/port만 허용하고 IMDS와 그 밖의 목적지는 거부한다.
ALLOY_EGRESS_SUBNET="$ALLOY_EGRESS_NETWORK_SUBNET" \
  AWS_REGION="${AWS_REGION}" \
  LOKI_URL="$LOKI_URL" \
  RESTART_STOPPED_ALLOY=false \
  /usr/local/sbin/configure-admin-alloy-egress
test -r /run/wes-alloy-egress/ready
export LOKI_HOST=$(cat /run/wes-alloy-egress/loki-host)
export LOKI_PRIMARY_IP=$(cat /run/wes-alloy-egress/loki-primary-ip)
export LOKI_PORT=$(cat /run/wes-alloy-egress/loki-port)
[ -n "$LOKI_HOST" ] && [ -n "$LOKI_PRIMARY_IP" ] && [ -n "$LOKI_PORT" ] || {
  echo "Loki 고정 host/IP/port를 준비하지 못했습니다" >&2
  exit 1
}

cd "$WORK_DIR"
docker compose -f docker-compose.admin-api.prod.yml \
  up -d --force-recreate --pull never
docker logout ghcr.io >/dev/null 2>&1 || true
REGISTRY_LOGGED_IN=false

EXPECTED_IMAGE="ghcr.io/${OWNER_LOWERCASE}/${ADMIN_IMAGE_BASENAME}:$TARGET_IMAGE_TAG"
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:8081/actuator/health" || true)
  if [ "$HTTP_CODE" = "200" ]; then
    RUNNING_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-admin-api)
    [ "$RUNNING_IMAGE" = "$EXPECTED_IMAGE" ] || {
      echo "실행 이미지 불일치: $RUNNING_IMAGE (expected $EXPECTED_IMAGE)" >&2
      exit 1
    }

    SESSION_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:8081/internal/admin/v1/auth/session" || true)
    [ "$SESSION_CODE" = "401" ] || {
      echo "관리자 인증 경계 확인 실패 (HTTP $SESSION_CODE)" >&2
      exit 1
    }

    ALLOY_RUNNING=$(docker inspect --format '{{.State.Running}}' wes-admin-alloy 2>/dev/null || true)
    [ "$ALLOY_RUNNING" = "true" ] || {
      echo "관리자 로그 전송 sidecar가 실행 중이 아닙니다" >&2
      docker logs --tail 100 wes-admin-alloy >&2 || true
      exit 1
    }

    EXPECTED_ALLOY_IMAGE="grafana/alloy:v1.18.1@sha256:0f4434c92b3e6cdac38bb129b344e1790c246f7b6e2eaffcc16a5fa363240e33"
    ALLOY_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-admin-alloy)
    [ "$ALLOY_IMAGE" = "$EXPECTED_ALLOY_IMAGE" ] || {
      echo "Alloy 이미지 pin 불일치" >&2
      exit 1
    }
    [ "$(docker inspect --format '{{.HostConfig.NetworkMode}}' wes-admin-alloy)" = "$ALLOY_EGRESS_NETWORK_NAME" ] || {
      echo "Alloy가 전용 egress network에만 연결되지 않았습니다" >&2
      exit 1
    }
    [ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' wes-admin-alloy)" = "true" ] || {
      echo "Alloy read-only root filesystem 검증 실패" >&2
      exit 1
    }
    [ "$(docker inspect --format '{{.Config.User}}' wes-admin-alloy)" = "473:473" ] || {
      echo "Alloy 비루트 사용자 검증 실패" >&2
      exit 1
    }
    [ "$(docker inspect --format '{{json .HostConfig.Dns}}' wes-admin-alloy)" = '["127.0.0.1"]' ] || {
      echo "Alloy DNS forwarding 차단 검증 실패" >&2
      exit 1
    }
    [ "$(docker inspect --format '{{json .HostConfig.ExtraHosts}}' wes-admin-alloy)" = "[\"$LOKI_HOST:$LOKI_PRIMARY_IP\"]" ] || {
      echo "Alloy Loki host pin 검증 실패" >&2
      exit 1
    }
    test -r /run/wes-alloy-egress/ready || {
      echo "Alloy egress 준비 marker가 없습니다" >&2
      exit 1
    }
    iptables -w 5 -C WES_ALLOY_OUT -d 169.254.169.254/32 -j REJECT >/dev/null
    iptables -w 5 -C WES_ALLOY_OUT -j REJECT >/dev/null
    iptables -w 5 -C WES_ALLOY_HOST -j REJECT >/dev/null
    FIRST_FORWARD_RULE=$(iptables -w 5 -S DOCKER-USER | awk '$1 == "-A" { print; exit }')
    FIRST_HOST_RULE=$(iptables -w 5 -S INPUT | awk '$1 == "-A" { print; exit }')
    [ "$FIRST_FORWARD_RULE" = \
      "-A DOCKER-USER -s $ALLOY_EGRESS_NETWORK_SUBNET -j WES_ALLOY_OUT" ] || {
        echo "Alloy DOCKER-USER jump가 첫 규칙이 아닙니다: $FIRST_FORWARD_RULE" >&2
        exit 1
      }
    [ "$FIRST_HOST_RULE" = \
      "-A INPUT -s $ALLOY_EGRESS_NETWORK_SUBNET -j WES_ALLOY_HOST" ] || {
        echo "Alloy INPUT jump가 첫 규칙이 아닙니다: $FIRST_HOST_RULE" >&2
        exit 1
      }
    systemctl is-enabled --quiet wes-admin-alloy-egress.service || {
      echo "Alloy egress boot policy가 활성화되지 않았습니다" >&2
      exit 1
    }

    docker image prune -f
    echo "관리자 API 기동 완료 (${i}번째 시도)"
    exit 0
  fi
  sleep 5
done

echo "150초 안에 관리자 API가 기동하지 못했습니다" >&2
docker logs --tail 100 wes-admin-api >&2
exit 1
