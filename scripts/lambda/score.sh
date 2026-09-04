#!/usr/bin/env bash
# 로컬 Lambda 대역 — score. 인자는 Lambda 페이로드 키 그대로: {"galleryId", "jobId", "force"}.
#
#   scripts/lambda/score.sh --gallery-id G [--job-id J] [--force]
#
# CLIP 벡터 + 미학·기술 점수 + 피사체를 photo_analysis 에 적는다. --job-id 가 있으면 AI repo 계약대로 잡을 열고(status
# RUNNING) 끝에서 categorize 를 이어 부른다 — 운영에서 score Lambda 가 categorize Lambda 를 EVENT 로 부르는 자리를
# CATEGORIZE_COMMAND(같은 디렉토리의 categorize.sh)가 대신한다. AI Phase 0(단계 claim, 체인 제거) 뒤에는 wes 가
# categorize 를 따로 부르므로 CATEGORIZE_COMMAND 를 비운다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"

GALLERY_ID=""; JOB_ID=""; FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --gallery-id) GALLERY_ID="$2"; shift ;;
    --job-id) JOB_ID="$2"; shift ;;
    --force) FORCE=true ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$GALLERY_ID" ] || { echo "--gallery-id 가 필요하다" >&2; exit 1; }

# shellcheck source=scripts/lib/ai-env.sh
. "$WES_ROOT/scripts/lib/ai-env.sh"
# shellcheck source=scripts/lib/ai-venv.sh
. "$WES_ROOT/scripts/lib/ai-venv.sh"
ai_env "$WES_ROOT"

PY="$(ai_python "$AI_ROOT" score)"
export CATEGORIZE_COMMAND="${CATEGORIZE_COMMAND-$WES_ROOT/scripts/lambda/categorize.sh}"
ARGS=(--gallery-id "$GALLERY_ID")
[ -n "$JOB_ID" ] && ARGS+=(--job-id "$JOB_ID")
[ "$FORCE" = true ] && ARGS+=(--force)
echo "[score] python -m score ${ARGS[*]}  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME bucket=$S3_BUCKET chain=${CATEGORIZE_COMMAND:-없음}"
exec "$PY" -m score "${ARGS[@]}"
