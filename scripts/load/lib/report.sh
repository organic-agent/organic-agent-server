#!/usr/bin/env bash
# 부하 측정 스크립트 공통 — 터미널에서 캡처해 산출물로 쓰도록 같은 모양으로 찍는다.
#
#   source "$(dirname "$0")/lib/report.sh"
#   report_parse_common "$@"; set -- "${REPORT_REST[@]}"     # local|remote|dev · --label · --title 를 뗀다
#   report_connect                                             # DB 접속 정보 (remote·dev 는 읽기 전용 세션)
#   report_begin "타임라인" "갤러리 101, 102"                    # 머리말 + 저장 시작
#   report_section "1. 단계별 경과"; report_sql -v ids='{1}' < scripts/load/timeline.sql
#   report_verdict pass "QA-1 단건 ≤ 7분" "6분 20초"
#   report_end
#
# 출력은 화면과 함께 $LOAD_RUNS_DIR/{label}-{시각}.txt 에 색 없이 저장된다(기본 docs/experiments/load-runs, gitignore).
#
# 대상: local = 로컬 docker pg · remote = 운영(터널 15432) · dev = dev 스택(터널 15433, `scripts/db-tunnel.sh dev`).
# 환경마다 다른 이름(SSM 프리픽스·AWS 리소스 접두사·GPU 태그·배포 워크플로)은 report_parse_common 이 아래 변수로 정한다.

REGION="ap-northeast-2"
REPORT_TARGET="local"; REPORT_LABEL=""; REPORT_TITLE=""; REPORT_REST=()
REPORT_CMD="$0 $*"

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_B=$'\033[1m'; C_DIM=$'\033[2m'; C_CY=$'\033[36m'; C_GR=$'\033[32m'; C_RD=$'\033[31m'; C_YL=$'\033[33m'; C_0=$'\033[0m'
else
  C_B=""; C_DIM=""; C_CY=""; C_GR=""; C_RD=""; C_YL=""; C_0=""
fi
RULE_HEAVY="━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
RULE_LIGHT="────────────────────────────────────────────────────────────────────────────────"

report_parse_common() {
  REPORT_CMD="$0 $*"
  while [ $# -gt 0 ]; do
    case "$1" in
      local|remote|dev) REPORT_TARGET="$1" ;;
      --label) REPORT_LABEL="$2"; shift ;;
      --title) REPORT_TITLE="$2"; shift ;;
      *) REPORT_REST+=("$1") ;;
    esac
    shift
  done
  report_target_vars
}

# 환경별 이름. local 은 GPU·Lambda 가 없어 운영 이름을 둔다(쓰지 않는다).
report_target_vars() {
  if [ "$REPORT_TARGET" = "dev" ]; then
    ENV_NAME="dev"; SSM_PREFIX="/wes/dev"; RESOURCE_PREFIX="wes-dev"; TUNNEL_PORT="15433"
    TUNNEL_CMD="scripts/db-tunnel.sh dev"; DEPLOY_WORKFLOW="[DEV] Build and Deploy"
  else
    ENV_NAME="prod"; SSM_PREFIX="/wes/prod"; RESOURCE_PREFIX="wes"; TUNNEL_PORT="15432"
    TUNNEL_CMD="scripts/db-tunnel.sh"; DEPLOY_WORKFLOW="[PROD] Build and Deploy"
  fi
  GPU_TAG_NAME="$RESOURCE_PREFIX-score-gpu"
}

report_connect() {
  command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1 \
    || { echo "docker가 꺼져 있다(psql을 컨테이너로 실행한다). Docker Desktop을 먼저 켤 것." >&2; exit 1; }
  if [ "$REPORT_TARGET" = "local" ]; then
    DB_HOST="host.docker.internal"; DB_PORT="5432"; DB_NAME="${LOAD_LOCAL_DB:-wes}"; DB_USER="wes"; DB_PASSWORD="wes"
  else
    lsof -nP -iTCP:"$TUNNEL_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
      || { echo "$TUNNEL_PORT 터널이 없다. 다른 터미널에서 $TUNNEL_CMD 를 먼저 띄울 것." >&2; exit 1; }
    DB_HOST="host.docker.internal"; DB_PORT="$TUNNEL_PORT"; DB_NAME="wes_db"; DB_USER="wes_admin"
    DB_PASSWORD=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/spring.datasource.password" \
      --with-decryption --query 'Parameter.Value' --output text)
  fi
}

# 표 하나. remote·dev 는 세션을 읽기 전용으로 연다 — 측정 쿼리가 실수로 쓰지 못하게.
report_sql() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" -e PGCLIENTENCODING=UTF8 -e TZ=Asia/Seoul \
    -e PGOPTIONS="-c default_transaction_read_only=on -c timezone=Asia/Seoul" \
    pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X \
      -P border=2 -P linestyle=unicode -P null='—' -P footer=off "$@" \
    | sed -e '/^$/d' -e 's/^/  /'
}

# 값 하나(머리말·판정용). 표 장식 없이.
report_scalar() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" -e PGCLIENTENCODING=UTF8 \
    -e PGOPTIONS="-c default_transaction_read_only=on -c timezone=Asia/Seoul" \
    pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}

report_begin() {
  local what="$1" target_desc="${2:-}"
  local repo_root; repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  local runs_dir="${LOAD_RUNS_DIR:-$repo_root/docs/experiments/load-runs}"
  mkdir -p "$runs_dir"
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  REPORT_FILE="$runs_dir/${REPORT_LABEL:-run}-$stamp.txt"
  # 화면엔 색, 파일엔 색 없이.
  exec > >(tee >(perl -pe 's/\e\[[0-9;]*m//g' > "$REPORT_FILE")) 2>&1

  local commit; commit="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo '?')"
  local branch; branch="$(git -C "$repo_root" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
  echo
  echo "${C_CY}${RULE_HEAVY}${C_0}"
  echo "${C_B}  ${REPORT_LABEL:-(라벨 없음)}  ·  ${REPORT_TITLE:-$what}${C_0}"
  echo "${C_CY}${RULE_HEAVY}${C_0}"
  # 한글은 두 칸이라 printf 폭 대신 공백을 맞춰 둔다.
  echo "  측정 시각  $(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST')"
  echo "  환경       $REPORT_TARGET ($DB_NAME$([ "$REPORT_TARGET" != local ] && echo ", $ENV_NAME, 읽기 전용"))"
  echo "  코드 기준  wes $branch @ $commit"
  echo "  측정       $what"
  if [ -n "$target_desc" ]; then echo "  대상       $target_desc"; fi
  echo "  명령       ${C_DIM}${REPORT_CMD}${C_0}"
}

report_section() {
  echo
  echo "${C_B}${C_CY}  ▸ $1${C_0}"
  if [ -n "${2:-}" ]; then echo "${C_DIM}    $2${C_0}"; fi
}

report_note() { echo "${C_DIM}    $1${C_0}"; }

# report_verdict pass|fail|info "기준" "실측"
report_verdict() {
  local mark
  case "$1" in
    pass) mark="${C_GR}✔ 통과${C_0}" ;;
    fail) mark="${C_RD}✘ 미달${C_0}" ;;
    *)    mark="${C_YL}• 참고${C_0}" ;;
  esac
  # 한글 폭 때문에 칸 맞춤 대신 화살표로 잇는다.
  echo "    $mark  $2  ${C_DIM}→${C_0}  ${C_B}$3${C_0}"
}

report_end() {
  echo
  echo "${C_CY}${RULE_LIGHT}${C_0}"
  echo "${C_DIM}  저장: ${REPORT_FILE}${C_0}"
  echo "${C_CY}${RULE_HEAVY}${C_0}"
  echo
}
