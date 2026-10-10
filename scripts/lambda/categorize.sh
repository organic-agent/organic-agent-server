#!/usr/bin/env bash
# 로컬 Lambda 대역 — categorize. 인자는 Lambda 페이로드 키 그대로: {"galleryId", "jobId", "conceptCount"?} · rank 모드 {"galleryId", "mode": "rank"}.
#
#   scripts/lambda/categorize.sh --gallery-id G [--job-id J] [--concept-count K]
#   scripts/lambda/categorize.sh --gallery-id G --mode rank      # 화질 점수가 다 찬 뒤 백분위·연사 대표 순위만(Bedrock 없음)
#
# 백분위·연사·임베딩 그룹을 photo_analysis 에, Bedrock 이름·배정을 concept_assignments(job_id) 에 적는다. 잡의 상태는 쓰지
# 않는다 — 실패했을 때 analysis_jobs.error 만 남기고, 잡을 닫는 것(DONE/FAILED)과 폴더 물질화는 wes 다.
# naming 은 항상 Bedrock 이라 --llm 고정 — 노트북 AWS 자격증명에 bedrock:InvokeModel 이 있어야 한다.
# --job-id 없이 부르면 잡 계약 밖의 그룹화라 배정은 저장하지 않는다(확인용).
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"

GALLERY_ID=""; JOB_ID=""; CONCEPT_COUNT=""; MODE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --gallery-id) GALLERY_ID="$2"; shift ;;
    --job-id) JOB_ID="$2"; shift ;;
    --concept-count) CONCEPT_COUNT="$2"; shift ;;
    --mode) MODE="$2"; shift ;;
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

# naming 모델. 조직 SCP가 `global.` 프로필(리전 미지정 호출)을 막아서 로컬은 미국 프로필을 미국 리전에서 부른다.
# DB·S3 는 AWS_REGION(ai-env.sh) 그대로이고 Bedrock 만 리전이 다르다. 환경변수로 덮어쓴다.
export BEDROCK_REGION="${BEDROCK_REGION:-us-east-1}"
export BEDROCK_MODEL_ID="${BEDROCK_MODEL_ID:-us.anthropic.claude-sonnet-4-6}"

# categorize 는 score venv 에 같이 깔린다(scripts/lib/ai-venv.sh) — 둘 다 numpy·psycopg 를 쓴다.
PY="$(ai_python "$AI_ROOT" score)"
ARGS=(--gallery-id "$GALLERY_ID" --llm)
[ -n "$JOB_ID" ] && ARGS+=(--job-id "$JOB_ID")
[ -n "$CONCEPT_COUNT" ] && ARGS+=(--concept-count "$CONCEPT_COUNT")
[ -n "$MODE" ] && ARGS+=(--mode "$MODE")
echo "[categorize] python -m categorize ${ARGS[*]}  bedrock=$BEDROCK_MODEL_ID@$BEDROCK_REGION  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
exec "$PY" -m categorize "${ARGS[@]}"
