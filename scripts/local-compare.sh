#!/usr/bin/env bash
# 비교샷 AI 판정을 로컬에서 동기로 돌린다 — 운영의 경량 Lambda(RequestResponse)를 노트북이 대신한다.
# wes local 프로필의 LocalProcessCompareInvoker가 부른다:
#
#   scripts/local-compare.sh <selectionId> <photoA> <photoB>
#
# **stdout에는 판정 JSON만 나온다** — wes가 그대로 파싱하므로 이 스크립트의 안내·오류는 전부
# stderr다(wes가 /tmp/wes-compare-<selectionId>.log 로 모은다). 손으로 돌려 볼 때도 같은 계약이다.
#
# 전제·접속 정보는 local-worker.sh와 같다: docker(pg), AI repo venv(photoselect/scripts/spike/.venv),
# AWS 자격증명(dev 버킷 + /wes/local/ 읽기 + Bedrock — 판정 LLM). Bedrock이 실패해도 AI 쪽이
# 템플릿 판정으로 응답하므로 여기서 막지 않는다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"
COMPOSE=(docker compose -f "$WES_ROOT/docker-compose.local.yml")
REGION="${AWS_REGION:-ap-northeast-2}"
BUCKET_PARAM="/wes/local/app.storage.bucket"

if [ $# -ne 3 ]; then
  echo "사용법: scripts/local-compare.sh <selectionId> <photoA> <photoB>" >&2
  exit 1
fi
SELECTION_ID="$1"; PHOTO_A="$2"; PHOTO_B="$3"

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
  "${COMPOSE[@]}" up -d postgres >/dev/null 2>&1
fi

# --- AI repo -------------------------------------------------------------------------------------
if [ ! -d "$AI_ROOT/photoselect" ]; then
  echo "AI repo를 찾지 못했다: $AI_ROOT (AI_ROOT 환경변수로 지정)" >&2
  exit 1
fi
AI_PY="$AI_ROOT/photoselect/scripts/spike/.venv/bin/python"
[ -x "$AI_PY" ] || AI_PY="python3"
if ! "$AI_PY" -c "import photoselect_v1" 2>/dev/null; then
  echo "[setup] pip install -e $AI_ROOT/photoselect (최초 1회)" >&2
  "$AI_PY" -m pip install -q -e "$AI_ROOT/photoselect" --no-deps >&2
fi

# compare는 torch를 import하지 않는다 — 동기 경로가 가벼운 이유다.
exec "$AI_PY" -m photoselect_v1 compare --db \
  --selection-id "$SELECTION_ID" --a "$PHOTO_A" --b "$PHOTO_B"
