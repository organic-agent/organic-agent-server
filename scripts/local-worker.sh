#!/usr/bin/env bash
# 로컬 AI 워커를 띄운다 — 웹의 "AI 분석"·"AI 추천" 버튼이 만든 잡(ai_analysis_jobs·ai_selection_jobs)을
# 집어 AI repo의 analyze(A)·draft(B)를 돌린다. 운영에서 GPU EC2가 하는 일을 노트북 터미널 하나가 대신한다.
#
#   scripts/local-worker.sh [--llm] [--poll N] [--once]
#
#   --llm      naming·추천 이유 문장을 Bedrock으로 (노트북 AWS 자격증명. 폴더화 잡은 필수)
#   --poll N   빈 큐일 때 대기 초 (기본 2)
#   --once     쌓인 잡만 처리하고 종료
#
# 전제·접속 정보는 local-ai.sh와 같다: docker(pg), AI repo venv(photoselect/scripts/spike/.venv),
# AWS 자격증명(dev 버킷 + /wes/local/ 읽기). S3_BUCKET은 환경변수 → SSM 순.
# 임베딩은 이 워커가 하지 않는다 — 로컬 wes(local 프로필)가 "임베딩 실행" 버튼에서 `scripts/local-ai.sh
# <galleryId> --only-embed`를 서브프로세스로 띄운다(LocalProcessEmbeddingInvoker). 즉 업로드부터 추천까지 웹 버튼이다.
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
    -h|--help) sed -n 2,16p "$0"; exit 0 ;;
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

# --- AI repo -------------------------------------------------------------------------------------
if [ ! -d "$AI_ROOT/photoselect" ]; then
  echo "AI repo를 찾지 못했다: $AI_ROOT (AI_ROOT 환경변수로 지정)" >&2
  exit 1
fi
AI_PY="$AI_ROOT/photoselect/scripts/spike/.venv/bin/python"
[ -x "$AI_PY" ] || AI_PY="python3"
# src 레이아웃이라 editable 설치가 있어야 -m photoselect_v1 이 잡힌다. 없으면 한 번 해 준다.
if ! "$AI_PY" -c "import photoselect_v1.worker" 2>/dev/null; then
  echo "[setup] pip install -e $AI_ROOT/photoselect (최초 1회)"
  "$AI_PY" -m pip install -q -e "$AI_ROOT/photoselect" --no-deps
fi


echo "worker  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME  bucket=$S3_BUCKET  args=${WORKER_ARGS[*]:-}"
echo "        웹에서 AI 분석 / AI 추천 버튼을 누르면 여기서 처리된다. Ctrl-C 로 종료."
exec "$AI_PY" -m photoselect_v1 worker "${WORKER_ARGS[@]}"
