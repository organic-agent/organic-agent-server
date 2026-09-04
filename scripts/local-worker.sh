#!/usr/bin/env bash
# 로컬 AI 워커를 띄운다 — 웹의 "AI 분석" 버튼이 만든 잡(ai_analysis_jobs)을 집어 AI repo의 폴더화 배치를 돌린다.
# 잡 mode 는 AI repo 워커가 두 단계로 매핑한다: FULL = SCORE(사진별 점수, torch) → CATEGORIZE(그룹 + naming),
# NAMING = CATEGORIZE. 운영에서 워커(EC2/Fargate)가 하는 일을 노트북 터미널 하나가 대신한다.
# "AI 추천"(ai_selection_jobs)과 비교샷은 wes 안에서 돈다 — 이 워커는 집지 않는다(#133·#131).
#
#   scripts/local-worker.sh [--llm] [--poll N] [--once]
#
#   --llm      naming(폴더 이름·배정)을 Bedrock으로 (노트북 AWS 자격증명). 잡 처리에는 필수 —
#              없으면 AI repo 워커가 잡을 시작하지 않고 FAILED 로 닫는다
#   --poll N   빈 큐일 때 대기 초 (기본 2)
#   --once     쌓인 잡만 처리하고 종료
#
# 전제·접속 정보는 local-ai.sh와 같다: docker(pg), python3, AWS 자격증명(dev 버킷 + /wes/local/ 읽기 + Bedrock).
# AI repo venv 는 photoselect/.venv 에 이 스크립트가 만든다(torch·open_clip·ARNIQA 의존, 첫 실행 수 GB).
# VLM(Ollama)은 더 이상 쓰지 않는다 — 이름 짓기는 Bedrock Sonnet 이다.
# 임베딩은 이 워커가 하지 않는다 — 로컬 wes(local 프로필)가 "임베딩 실행" 버튼에서 `scripts/local-ai.sh
# <galleryId> --only-embed`를 서브프로세스로 띄운다(LocalProcessEmbeddingInvoker). 즉 업로드부터 폴더까지 웹 버튼이다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"
COMPOSE=(docker compose -f "$WES_ROOT/docker-compose.local.yml")
REGION="${AWS_REGION:-ap-northeast-2}"
BUCKET_PARAM="/wes/local/app.storage.bucket"

WORKER_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --llm|--once) WORKER_ARGS+=("$1") ;;
    --poll) WORKER_ARGS+=(--poll "$2"); shift ;;
    -h|--help) sed -n 2,18p "$0"; exit 0 ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
  shift
done

# --- 접속 정보 ---------------------------------------------------------------------------------
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-wes}"
export DB_USER="${DB_USER:-wes}"
export DB_PASSWORD="${DB_PASSWORD:-wes}"
export DB_SSLMODE="${DB_SSLMODE:-disable}"   # 로컬 pg는 TLS가 없다. RDS 터널이면 require
if [ -z "${S3_BUCKET:-}" ]; then
  S3_BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$BUCKET_PARAM" \
    --query Parameter.Value --output text 2>/dev/null || true)
  if [ -z "$S3_BUCKET" ] || [ "$S3_BUCKET" = "None" ]; then
    echo "S3_BUCKET을 정하지 못했다. 인프라 apply로 $BUCKET_PARAM 이 생겼는지, AWS 자격증명이 있는지 확인." >&2
    exit 1
  fi
fi
export S3_BUCKET
export AWS_REGION="$REGION"

# --- pg -----------------------------------------------------------------------------------------
if [ "$DB_HOST" = "localhost" ] && [ "$DB_PORT" = "5432" ]; then
  "${COMPOSE[@]}" up -d postgres >/dev/null
fi

# --- AI repo (photoselect/, 평탄 패키지) ----------------------------------------------------------
# shellcheck source=scripts/lib/ai-venv.sh
. "$WES_ROOT/scripts/lib/ai-venv.sh"
AI_PY="$(photoselect_python "$AI_ROOT")"

echo "worker  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME  bucket=$S3_BUCKET  args=${WORKER_ARGS[*]:-}"
echo "        웹에서 AI 분석 버튼을 누르면 여기서 처리된다(AI 추천·비교샷은 wes가 직접 돈다). Ctrl-C 로 종료."
exec "$AI_PY" -m photoselect worker "${WORKER_ARGS[@]}"
