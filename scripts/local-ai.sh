#!/usr/bin/env bash
# 로컬 AI 파이프라인을 한 번에 돌린다: 임베딩(미리보기 PUT → DINOv3) → 폴더화(SCORE 점수 → CATEGORIZE 그룹 + Bedrock 이름).
#
# 운영에서 Lambda·워커가 하는 일을 노트북이 대신한다. 로컬 wes(local 프로필)는 "임베딩 실행" 버튼에서 이 스크립트를
# `--only-embed` 로 서브프로세스로 띄운다(LocalProcessEmbeddingInvoker). 폴더화는 웹의 "AI 분석" 버튼 → local-worker.sh 가
# 정식 경로이고, 이 스크립트의 분석 단계는 잡 없이 한 번에 돌려 보는 지름길이다. 추천·비교샷은 wes 안에서 돈다.
#
#   scripts/local-ai.sh <galleryId> [--force] [--skip-embed] [--skip-analyze] [--only-embed]
#
#   --force         이미 벡터·점수가 있는 사진도 다시 계산한다 (임베더 --force, SCORE --force)
#   --only-embed    = --skip-analyze
#
# 전제: docker(pg), python3, AWS 자격증명(dev 버킷 + /wes/local/ 읽기 + Bedrock — 분석 단계의 naming).
# AI repo venv 는 embedder/.venv · photoselect/.venv 에 이 스크립트가 만든다. VLM(Ollama)은 더 이상 쓰지 않는다.
# DB는 docker-compose.local.yml의 pg(localhost:5432 wes/wes/wes)가 기본이고 DB_* 환경변수로 덮어쓴다.
# S3_BUCKET은 환경변수 → /wes/local/app.storage.bucket(인프라 apply가 기록) 순으로 정한다.
# 운영 RDS를 가리키게 하지 마라 — 미확정 스키마 시험은 부술 수 있는 DB에서만 한다.
set -euo pipefail

WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"
COMPOSE=(docker compose -f "$WES_ROOT/docker-compose.local.yml")
REGION="${AWS_REGION:-ap-northeast-2}"
BUCKET_PARAM="/wes/local/app.storage.bucket"

GALLERY_ID=""
FORCE=false
SKIP_EMBED=false; SKIP_ANALYZE=false

while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=true ;;
    --skip-embed) SKIP_EMBED=true ;;
    --skip-analyze) SKIP_ANALYZE=true ;;
    --only-embed) SKIP_ANALYZE=true ;;
    -h|--help) sed -n 2,15p "$0"; exit 0 ;;
    -*) echo "모르는 옵션: $1" >&2; exit 1 ;;
    *) GALLERY_ID="$1" ;;
  esac
  shift
done
if [ -z "$GALLERY_ID" ]; then
  echo "사용법: scripts/local-ai.sh <galleryId> [옵션]  (--help)" >&2
  exit 1
fi

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

echo "galleryId=$GALLERY_ID  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME  bucket=$S3_BUCKET"

# --- pg -----------------------------------------------------------------------------------------
if [ "$DB_HOST" = "localhost" ] && [ "$DB_PORT" = "5432" ]; then
  "${COMPOSE[@]}" up -d postgres >/dev/null
fi

# --- 1. 임베딩 (AI repo embedder/, DINOv3) ------------------------------------------------------
# embedder는 organic-agent-ai repo로 이관됐다(#123). venv는 embedder/.venv 에 한 번 만든다.
# 리눅스에서는 CUDA 빌드가 딸려오지 않게 CPU 인덱스로, 맥은 pypi 기본 빌드가 MPS를 잡는다.
EMBEDDER_DIR="$AI_ROOT/embedder"
EMBEDDER_PY="$EMBEDDER_DIR/.venv/bin/python"
if [ "$SKIP_EMBED" = false ]; then
  if [ ! -x "$EMBEDDER_PY" ]; then
    echo "[embed] venv 생성: $EMBEDDER_DIR/.venv"
    python3 -m venv "$EMBEDDER_DIR/.venv"
    if [ "$(uname -s)" = "Linux" ]; then
      "$EMBEDDER_PY" -m pip install -q torch torchvision --index-url https://download.pytorch.org/whl/cpu
    else
      "$EMBEDDER_PY" -m pip install -q torch torchvision
    fi
    "$EMBEDDER_PY" -m pip install -q -r "$EMBEDDER_DIR/requirements.txt"
  fi
  EMBED_ARGS=(--gallery-id "$GALLERY_ID")
  [ "$FORCE" = true ] && EMBED_ARGS+=(--force)
  echo "[embed] python -m embedder ${EMBED_ARGS[*]}"
  (cd "$EMBEDDER_DIR" && "$EMBEDDER_PY" -m embedder "${EMBED_ARGS[@]}")
fi

# --- AI repo CLI (photoselect/, 평탄 패키지) ------------------------------------------------------
if [ "$SKIP_ANALYZE" = true ]; then
  echo "끝. 임베딩만 돌렸다."
  exit 0
fi

# shellcheck source=scripts/lib/ai-venv.sh
. "$WES_ROOT/scripts/lib/ai-venv.sh"
AI_PY="$(photoselect_python "$AI_ROOT")"

# --- 2. 폴더화 (SCORE → CATEGORIZE → naming, Bedrock) ---------------------------------------------
# `analyze` 는 wes FULL 잡과 같은 순서(score → categorize)의 alias 다. --llm 이 있어야 naming 까지 간다 —
# 없으면 그룹까지만 만들고 naming 은 skipped 로 남아 폴더 세트가 생기지 않는다.
ANALYZE_ARGS=(--gallery "$GALLERY_ID" --db --llm)
[ "$FORCE" = true ] && ANALYZE_ARGS+=(--force)
echo "[analyze] python -m photoselect analyze ${ANALYZE_ARGS[*]}"
"$AI_PY" -m photoselect analyze "${ANALYZE_ARGS[@]}"

echo "확인(API): POST /api/v1/galleries/$GALLERY_ID/concept-folders/ai (작가 토큰) — 배정이 있으면 AI 폴더 세트가 생긴다"
echo "끝. 확인: psql -h $DB_HOST -p $DB_PORT -U $DB_USER $DB_NAME -c \"SELECT count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded, count(embed_group_id) AS categorized FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = $GALLERY_ID\""
