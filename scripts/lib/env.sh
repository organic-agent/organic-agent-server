#!/usr/bin/env bash
# 환경 이름 → 값 표. 운영·dev 이름(SSM 프리픽스·포트·리소스 접두사·주소)은 이 파일에만 적는다.
# 대상: 없음(값만 정하는 라이브러리 — source 로 읽는다)
# 바꾸는 것: 없음(읽기 전용)
#
#   source "$(dirname "$0")/../lib/env.sh"      # scripts/<폴더>/x.sh 에서. scripts/load/<폴더>/ 는 load/lib/report.sh 가 대신 읽는다
#   env_take "$1" && shift                      # 첫 인자가 환경이면 ENV_TARGET 에 담는다
#   env_require                                 # 빠졌으면 "대상 환경이 필요하다: local | dev | prod" 로 멈춘다
#   env_db_connect                              # DB_HOST·DB_PORT·DB_PASSWORD (dev·prod 는 터널 확인 + SSM 비밀번호)
#
# 환경은 늘 명령 줄에 보이게 한다 — 기본값이 없다. 옛 이름 `remote` 는 오류로 끊는다(옛 기록의 명령이 조용히 운영에 닿지 않게).

WES_REGION="ap-northeast-2"
REGION="${REGION:-$WES_REGION}"
ENV_TARGET="${ENV_TARGET:-}"
ENV_CHOICES="local | dev | prod"

# 인자 하나가 환경 이름이면 ENV_TARGET 에 담고 0, 아니면 1. `remote` 는 바로 멈춘다.
env_take() {
  case "${1:-}" in
    local|dev|prod) ENV_TARGET="$1"; env_vars; return 0 ;;
    remote)
      echo "환경 이름 'remote' 는 'prod' 로 바뀌었다(운영). 운영이 맞으면 prod 로 다시 실행할 것." >&2
      exit 2 ;;
    *) return 1 ;;
  esac
}

env_require() {
  if [ -z "$ENV_TARGET" ]; then
    echo "대상 환경이 필요하다: $ENV_CHOICES (예: $(basename "$0") dev …)" >&2
    exit 2
  fi
}

# 환경별 값. local 은 운영 이름을 빌리지 않는다 — 로컬 실행이 운영 리소스를 찾아가지 않게.
env_vars() {
  case "$ENV_TARGET" in
    local)
      ENV_NAME="local"; SSM_PREFIX="/wes/local"; RESOURCE_PREFIX="wes-local"
      TUNNEL_PORT=""; TUNNEL_CMD=""; INSTANCE_TAG=""; DB_IDENTIFIER=""
      DB_NAME="${LOAD_LOCAL_DB:-wes}"; DB_USER="wes"
      API_BASE="http://localhost:8080"; DEPLOY_WORKFLOW="" ;;
    dev)
      ENV_NAME="dev"; SSM_PREFIX="/wes/dev"; RESOURCE_PREFIX="wes-dev"
      TUNNEL_PORT="15433"; TUNNEL_CMD="scripts/dev-tools/db-tunnel.sh dev"; INSTANCE_TAG="wes-dev-app"; DB_IDENTIFIER="wes-dev-db"
      DB_NAME="wes_db"; DB_USER="wes_admin"
      API_BASE="https://dev.api.easyselect.kr"; DEPLOY_WORKFLOW="[DEV] Build and Deploy" ;;
    prod)
      ENV_NAME="prod"; SSM_PREFIX="/wes/prod"; RESOURCE_PREFIX="wes"
      TUNNEL_PORT="15432"; TUNNEL_CMD="scripts/dev-tools/db-tunnel.sh prod"; INSTANCE_TAG="wes-app"; DB_IDENTIFIER="wes-db"
      DB_NAME="wes_db"; DB_USER="wes_admin"
      API_BASE="https://api.easyselect.kr"; DEPLOY_WORKFLOW="[PROD] Build and Deploy" ;;
  esac
  GPU_TAG_NAME="$RESOURCE_PREFIX-score-gpu"
  BUCKET_PARAM="$SSM_PREFIX/app.storage.bucket"
  # 앱 서버의 배포 폴더. deploy-public-ssm.sh 의 WORK_DIR 과 같고 dev·운영이 같은 슬롯 이름을 쓴다.
  APP_DIR="/opt/wes-prod"
}

# DB 접속 정보. psql 은 컨테이너로 돌리므로 호스트는 host.docker.internal.
env_db_connect() {
  env_require
  command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1 \
    || { echo "docker가 꺼져 있다(psql을 컨테이너로 실행한다). Docker Desktop을 먼저 켤 것." >&2; exit 1; }
  DB_HOST="host.docker.internal"
  if [ "$ENV_TARGET" = "local" ]; then
    DB_PORT="5432"; DB_PASSWORD="wes"
  else
    lsof -nP -iTCP:"$TUNNEL_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
      || { echo "$TUNNEL_PORT 터널이 없다. 다른 터미널에서 $TUNNEL_CMD 를 먼저 띄울 것." >&2; exit 1; }
    DB_PORT="$TUNNEL_PORT"
    DB_PASSWORD=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/spring.datasource.password" \
      --with-decryption --query 'Parameter.Value' --output text)
  fi
}

# 다른 환경의 값을 잠깐 빌린다 — 두 환경을 일부러 함께 쓰는 스크립트(운영 버킷 읽기 → dev 버킷 쓰기)용.
#   env_value_of prod SSM_PREFIX   → /wes/prod
env_value_of() {
  local saved="$ENV_TARGET" out
  ENV_TARGET="$1"; env_vars; out="${!2}"
  ENV_TARGET="$saved"; [ -n "$saved" ] && env_vars
  printf '%s' "$out"
}
