#!/usr/bin/env bash
# 로컬 Lambda 대역 — embedder. 운영 Lambda 함수 하나 = 이 디렉토리의 스크립트 하나(AI repo 최상위 모듈과 같은 이름).
# 인자는 Lambda 페이로드 키를 그대로 옮긴 것이다: {"galleryId", "force", "analysisJobId"}.
#
#   scripts/lambda/embedder.sh --gallery-id G [--force] [--job-id J]
#
# 미리보기 PUT → DINOv3 벡터 → EXIF 를 photos·photo_analysis 에 적는다. 잡(--job-id)은 받기만 하고 쓰지 않는다 —
# EMBED 단계의 완료는 wes 가 photo_analysis 를 관측해 판정한다(임베더는 잡 행을 모른다). 기다리지 않고 띄우는 것은
# 부르는 쪽(wes LocalProcessStageInvoker)의 일이고, 이 스크립트 자체는 끝날 때까지 돈다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"

GALLERY_ID=""; FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --gallery-id) GALLERY_ID="$2"; shift ;;
    --force) FORCE=true ;;
    --job-id) shift ;;   # 임베더는 잡을 쓰지 않는다
    -h|--help) sed -n 2,10p "$0"; exit 0 ;;
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

PY="$(ai_python "$AI_ROOT" embedder)"
ARGS=(--gallery-id "$GALLERY_ID")
[ "$FORCE" = true ] && ARGS+=(--force)
echo "[embedder] python -m embedder ${ARGS[*]}  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME bucket=$S3_BUCKET"
cd "$AI_ROOT/embedder" && exec "$PY" -m embedder "${ARGS[@]}"
