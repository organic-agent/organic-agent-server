#!/bin/sh

set -eu

# compose 파일의 ${...}를 채울 값. 서버에 .env를 남기지 않는다.
export OWNER_LOWERCASE="${OWNER_LOWERCASE}"
export IMAGE_TAG="${IMAGE_TAG}"
TARGET_IMAGE_TAG="$IMAGE_TAG"
WORK_DIR=/opt/wes-prod
ROLLBACK_DIR="$WORK_DIR/.public-rollback"
CANDIDATE_DIR="$WORK_DIR/.public-candidate"
PREVIOUS_RELEASE_AVAILABLE=false
ROLLBACK_ARMED=false
REGISTRY_LOGGED_IN=false
PREVIOUS_IMAGE=
PREVIOUS_IMAGE_ID=
PREVIOUS_IMAGE_TAG=
PREVIOUS_SERVICE=
EXPECTED_PREVIOUS_SERVICE=
TARGET_SERVICE=wes-api

handoff_public_service() (
  set -eu
  DESIRED_SERVICE="$1"
  case "$DESIRED_SERVICE" in
    wes-api|wes-server) ;;
    *)
      echo "허용되지 않은 공개 API compose service handoff: $DESIRED_SERVICE" >&2
      return 1
      ;;
  esac

  docker inspect wes-app >/dev/null 2>&1 || return 0
  CURRENT_SERVICE=$(docker inspect \
    --format '{{ index .Config.Labels "com.docker.compose.service" }}' wes-app)
  [ "$CURRENT_SERVICE" = "$DESIRED_SERVICE" ] && return 0

  # The legacy and split compose files use different service keys but the
  # same fixed container_name. Remove only that captured container after
  # rollback is armed so either direction can recreate it without conflict.
  docker update --restart=no wes-app >/dev/null 2>&1 || true
  docker rm -f wes-app >/dev/null
  if docker inspect wes-app >/dev/null 2>&1; then
    echo "공개 API service handoff 뒤에도 wes-app 컨테이너가 남아 있습니다" >&2
    return 1
  fi
)

rollback_public_release() (
  set -eu

  echo "공개 API 이전 릴리스 롤백을 시작합니다" >&2
  install -m 0644 "$ROLLBACK_DIR/docker-compose.prod.yml" \
    "$WORK_DIR/.docker-compose.prod.yml.rollback"
  install -m 0644 "$ROLLBACK_DIR/config.alloy" \
    "$WORK_DIR/.config.alloy.rollback"
  mv -f "$WORK_DIR/.docker-compose.prod.yml.rollback" \
    "$WORK_DIR/docker-compose.prod.yml"
  mv -f "$WORK_DIR/.config.alloy.rollback" "$WORK_DIR/config.alloy"
  cmp -s "$ROLLBACK_DIR/docker-compose.prod.yml" "$WORK_DIR/docker-compose.prod.yml"
  cmp -s "$ROLLBACK_DIR/config.alloy" "$WORK_DIR/config.alloy"

  docker image inspect "$PREVIOUS_IMAGE_ID" >/dev/null
  docker image tag "$PREVIOUS_IMAGE_ID" "$PREVIOUS_IMAGE"
  export IMAGE_TAG="$PREVIOUS_IMAGE_TAG"
  handoff_public_service "$PREVIOUS_SERVICE"

  cd "$WORK_DIR"
  docker compose -f docker-compose.prod.yml \
    up -d --force-recreate --pull never "$PREVIOUS_SERVICE"
  ROLLED_BACK_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-app)
  ROLLED_BACK_IMAGE_ID=$(docker inspect --format '{{.Image}}' wes-app)
  [ "$ROLLED_BACK_IMAGE" = "$PREVIOUS_IMAGE" ] || {
    echo "공개 API 롤백 이미지 불일치: $ROLLED_BACK_IMAGE (expected $PREVIOUS_IMAGE)" >&2
    return 1
  }
  [ "$ROLLED_BACK_IMAGE_ID" = "$PREVIOUS_IMAGE_ID" ] || {
    echo "공개 API 롤백 이미지 ID 불일치: $ROLLED_BACK_IMAGE_ID (expected $PREVIOUS_IMAGE_ID)" >&2
    return 1
  }

  for rollback_attempt in $(seq 1 30); do
    ROLLBACK_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:8080/actuator/health" || true)
    if [ "$ROLLBACK_HTTP_CODE" = "200" ]; then
      echo "공개 API 이전 릴리스 복구 완료 (${rollback_attempt}번째 시도)" >&2
      return 0
    fi
    sleep 5
  done

  echo "이전 공개 API 이미지로 복구했지만 8080 health 검증에 실패했습니다" >&2
  docker logs --tail 100 wes-app >&2 || true
  return 1
)

on_public_deploy_exit() {
  ORIGINAL_STATUS=$?
  trap - EXIT HUP INT TERM
  set +e

  if [ "$ORIGINAL_STATUS" -ne 0 ]; then
    if [ "$ROLLBACK_ARMED" = "true" ] && [ "$PREVIOUS_RELEASE_AVAILABLE" = "true" ]; then
      rollback_public_release
      [ "$?" -eq 0 ] || echo "공개 API 롤백도 실패했습니다; 수동 복구가 필요합니다" >&2
    elif [ "$ROLLBACK_ARMED" = "true" ]; then
      echo "첫 공개 API 배포여서 이전 wes-app 컨테이너가 없습니다; 실패 상태를 유지합니다" >&2
    else
      echo "공개 API 변경 전 실패하여 기존 릴리스는 그대로 유지됩니다" >&2
    fi
  fi

  if [ "$REGISTRY_LOGGED_IN" = "true" ]; then
    docker logout ghcr.io >/dev/null 2>&1 || true
  fi
  exit "$ORIGINAL_STATUS"
}

trap on_public_deploy_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$WORK_DIR" "$WORK_DIR/logs" "$ROLLBACK_DIR"
install -d -m 0700 "$CANDIDATE_DIR"

if docker inspect wes-app >/dev/null 2>&1; then
  [ -f "$WORK_DIR/docker-compose.prod.yml" ] && [ -f "$WORK_DIR/config.alloy" ] || {
    echo "기존 공개 API는 있지만 이전 compose/Alloy 설정이 없어 안전하게 배포할 수 없습니다" >&2
    exit 1
  }
  PREVIOUS_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-app)
  PREVIOUS_IMAGE_ID=$(docker inspect --format '{{.Image}}' wes-app)
  PREVIOUS_SERVICE=$(docker inspect \
    --format '{{ index .Config.Labels "com.docker.compose.service" }}' wes-app)
  PREVIOUS_IMAGE_PREFIX="ghcr.io/${OWNER_LOWERCASE}/${PUBLIC_IMAGE_BASENAME}:"
  PREVIOUS_LEGACY_IMAGE="ghcr.io/${OWNER_LOWERCASE}/wes-server:latest"
  case "$PREVIOUS_IMAGE" in
    "$PREVIOUS_IMAGE_PREFIX"*)
      PREVIOUS_IMAGE_TAG=${PREVIOUS_IMAGE#"$PREVIOUS_IMAGE_PREFIX"}
      EXPECTED_PREVIOUS_SERVICE=wes-api
      ;;
    "$PREVIOUS_LEGACY_IMAGE")
      PREVIOUS_IMAGE_TAG=latest
      EXPECTED_PREVIOUS_SERVICE=wes-server
      ;;
    *)
      echo "기존 공개 API 이미지가 예상 저장소에 속하지 않습니다: $PREVIOUS_IMAGE" >&2
      exit 1
      ;;
  esac
  [ -n "$PREVIOUS_IMAGE_TAG" ]
  [ "$PREVIOUS_SERVICE" = "$EXPECTED_PREVIOUS_SERVICE" ] || {
    echo "기존 공개 API compose service 불일치: $PREVIOUS_SERVICE" >&2
    exit 1
  }
  docker image inspect "$PREVIOUS_IMAGE_ID" >/dev/null

  PREVIOUS_HEALTHY=false
  for preflight_attempt in $(seq 1 5); do
    PREFLIGHT_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:8080/actuator/health" || true)
    if [ "$PREFLIGHT_HTTP_CODE" = "200" ]; then
      PREVIOUS_HEALTHY=true
      break
    fi
    sleep 2
  done
  [ "$PREVIOUS_HEALTHY" = "true" ] || {
    echo "기존 공개 API가 healthy하지 않아 검증 가능한 롤백 기준점이 없습니다" >&2
    exit 1
  }

  install -m 0600 "$WORK_DIR/docker-compose.prod.yml" \
    "$ROLLBACK_DIR/docker-compose.prod.yml"
  install -m 0600 "$WORK_DIR/config.alloy" "$ROLLBACK_DIR/config.alloy"
  printf '%s\n' "$PREVIOUS_IMAGE" > "$ROLLBACK_DIR/previous-image"
  printf '%s\n' "$PREVIOUS_IMAGE_ID" > "$ROLLBACK_DIR/previous-image-id"
  printf '%s\n' "$PREVIOUS_IMAGE_TAG" > "$ROLLBACK_DIR/previous-image-tag"
  chmod 0600 "$ROLLBACK_DIR/previous-image" \
    "$ROLLBACK_DIR/previous-image-id" "$ROLLBACK_DIR/previous-image-tag"
  PREVIOUS_RELEASE_AVAILABLE=true
else
  echo "첫 공개 API 배포: 이전 wes-app 컨테이너가 없어 롤백 기준점이 없습니다" >&2
fi

# alloy가 로그를 보낼 Loki 주소. 인프라(모니터링 EC2)가 Parameter Store에 기록한다.
# 인스턴스 롤은 /wes/prod/* 읽기 권한이 있고 aws-cli는 user_data가 깔아 둔다.
# 아직 없으면 alloy는 뜨되 전송만 실패하고, 앱 배포는 그대로 진행된다.
export LOKI_URL=$(aws ssm get-parameter --region ${AWS_REGION} \
  --name /wes/prod/app.logging.loki-url --query Parameter.Value --output text 2>/dev/null || true)
[ -n "$LOKI_URL" ] || echo "경고: /wes/prod/app.logging.loki-url 파라미터가 없어 로그가 Loki로 전송되지 않습니다" >&2

echo "${COMPOSE_B64}" | base64 -d > "$CANDIDATE_DIR/docker-compose.prod.yml"
echo "${ALLOY_B64}" | base64 -d > "$CANDIDATE_DIR/config.alloy"
chmod 0600 "$CANDIDATE_DIR/docker-compose.prod.yml" "$CANDIDATE_DIR/config.alloy"
[ -s "$CANDIDATE_DIR/docker-compose.prod.yml" ] && \
  [ -s "$CANDIDATE_DIR/config.alloy" ]
docker compose --project-directory "$WORK_DIR" \
  -f "$CANDIDATE_DIR/docker-compose.prod.yml" config --quiet

echo "$GITHUB_TOKEN" \
  | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
REGISTRY_LOGGED_IN=true

docker compose --project-directory "$WORK_DIR" \
  -f "$CANDIDATE_DIR/docker-compose.prod.yml" pull

install -m 0644 "$CANDIDATE_DIR/docker-compose.prod.yml" \
  "$WORK_DIR/.docker-compose.prod.yml.next"
install -m 0644 "$CANDIDATE_DIR/config.alloy" "$WORK_DIR/.config.alloy.next"
ROLLBACK_ARMED=true
mv -f "$WORK_DIR/.docker-compose.prod.yml.next" "$WORK_DIR/docker-compose.prod.yml"
mv -f "$WORK_DIR/.config.alloy.next" "$WORK_DIR/config.alloy"

cd "$WORK_DIR"
handoff_public_service "$TARGET_SERVICE"
# The legacy root container created level directories and current log files as root.
# Migrate them exactly once, after the legacy writer is gone. Later wes-api releases
# already own their log tree and must not be traversed while that writer is running.
if [ "$PREVIOUS_RELEASE_AVAILABLE" = "true" ] && [ "$PREVIOUS_SERVICE" = "wes-server" ]; then
  if docker inspect wes-app >/dev/null 2>&1; then
    echo "레거시 로그 권한 이전 전에 wes-app 컨테이너가 남아 있습니다" >&2
    exit 1
  fi
  [ -d "$WORK_DIR/logs" ] && [ ! -L "$WORK_DIR/logs" ] || {
    echo "레거시 로그 루트가 실제 디렉터리가 아닙니다" >&2
    exit 1
  }
  LEGACY_LOG_SYMLINK=$(find "$WORK_DIR/logs" -xdev -type l -print -quit)
  [ -z "$LEGACY_LOG_SYMLINK" ] || {
    echo "레거시 로그 트리에 허용되지 않은 심볼릭 링크가 있습니다: $LEGACY_LOG_SYMLINK" >&2
    exit 1
  }
  find "$WORK_DIR/logs" -xdev \( -type d -o -type f \) \
    -exec chown -h 10001:10001 {} +
  install -d -m 0755 -o 10001 -g 10001 \
    "$WORK_DIR/logs/info" "$WORK_DIR/logs/warn" "$WORK_DIR/logs/error"
elif [ "$PREVIOUS_RELEASE_AVAILABLE" = "false" ]; then
  # A true first release has no writer and no legacy files to recurse over.
  install -d -m 0755 -o 10001 -g 10001 \
    "$WORK_DIR/logs" "$WORK_DIR/logs/info" "$WORK_DIR/logs/warn" "$WORK_DIR/logs/error"
fi
docker compose -f docker-compose.prod.yml up -d --force-recreate --pull never
docker logout ghcr.io >/dev/null 2>&1 || true
REGISTRY_LOGGED_IN=false

RUNNING_IMAGE=$(docker inspect --format '{{.Config.Image}}' wes-app)
EXPECTED_IMAGE="ghcr.io/${OWNER_LOWERCASE}/${PUBLIC_IMAGE_BASENAME}:$TARGET_IMAGE_TAG"
[ "$RUNNING_IMAGE" = "$EXPECTED_IMAGE" ] || {
  echo "실행 이미지 불일치: $RUNNING_IMAGE (expected $EXPECTED_IMAGE)" >&2
  exit 1
}

# 기동에 시간이 걸린다(Parameter Store 조회 + 컨텍스트 초기화).
# 상태 코드로 판단한다. 200 = UP, 503 = DOWN.
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:8080/actuator/health" || true)
  if [ "$HTTP_CODE" = "200" ]; then
    docker image prune -f
    echo "기동 완료 (${i}번째 시도)"
    exit 0
  fi
  sleep 5
done

echo "150초 안에 기동하지 못했습니다" >&2
docker logs --tail 50 wes-app >&2
exit 1
