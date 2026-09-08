#!/usr/bin/env bash
# 로컬 Lambda 대역 — score(폴백). 인자는 Lambda 페이로드 키 그대로: {"galleryId", "photoIds"}.
#
#   scripts/lambda/score.sh --gallery-id G --photo-ids 1,2,3
#
# 사진 목록의 CLIP 벡터 + 미학·기술 점수 + 피사체를 photo_analysis 에 적고 끝난다 — 잡·체인·샤딩 없음. 운영에서는 GPU 워커가
# 점수를 내고 이 Lambda 는 워커가 없을 때(app.analysis.gpu.enabled=false) wes 가 부르는 폴백이다.
# --photo-ids 없이 부르면 갤러리 전체(옛 경로, scripts/local-ai.sh 용). categorize 는 wes 가 따로 부른다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"

GALLERY_ID=""; PHOTO_IDS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --gallery-id) GALLERY_ID="$2"; shift ;;
    --photo-ids) PHOTO_IDS="$2"; shift ;;
    -h|--help) sed -n 2,8p "$0"; exit 0 ;;
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
# 옛 경로(갤러리 전체)가 categorize 를 체인으로 부르지 않게 비운다 — categorize 는 wes 잡이 부른다.
export CATEGORIZE_COMMAND=""
ARGS=(--gallery-id "$GALLERY_ID")
[ -n "$PHOTO_IDS" ] && ARGS+=(--photo-ids "$PHOTO_IDS")
echo "[score] python -m score ${ARGS[*]}  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME bucket=$S3_BUCKET"
exec "$PY" -m score "${ARGS[@]}"
