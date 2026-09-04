#!/usr/bin/env bash
# 로컬 AI 파이프라인을 한 번에 돌린다: 임베딩(미리보기 PUT → DINOv3) → 점수(score) → 그룹·이름(categorize).
#
# 운영에서 Lambda 셋(embedder → score → categorize)이 체인으로 하는 일을 노트북이 순서대로 대신한다. 로컬 wes(local
# 프로필)는 "임베딩 실행" 버튼에서 이 스크립트를 `--only-embed` 로 서브프로세스로 띄운다(LocalProcessEmbeddingInvoker).
# 폴더화의 정식 경로는 웹의 "AI 분석" 버튼 → local-worker.sh(잡)다. 이 스크립트의 점수·그룹 단계는 잡 없이 한 번에
# 돌려 보는 지름길이라 **배정(ai_concept_assignments)은 저장되지 않는다** — 폴더 세트가 필요하면 워커 경로를 쓴다.
# 추천·비교샷은 wes 안에서 돈다.
#
#   scripts/local-ai.sh <galleryId> [--force] [--skip-embed] [--skip-analyze] [--only-embed]
#
#   --force         이미 벡터·점수가 있는 사진도 다시 계산한다 (embedder --force, score --force)
#   --only-embed    = --skip-analyze
#
# 전제: docker(pg), python3, AWS 자격증명(dev 버킷 + /wes/local/ 읽기 + Bedrock — categorize 의 naming).
# AI repo venv 는 embedder/.venv · score/.venv(categorize 포함)에 이 스크립트가 만든다(scripts/lib/ai-venv.sh).
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
    -h|--help) sed -n 2,18p "$0"; exit 0 ;;
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

# shellcheck source=scripts/lib/ai-venv.sh
. "$WES_ROOT/scripts/lib/ai-venv.sh"

# --- 1. 임베딩 (AI repo embedder/, DINOv3) ------------------------------------------------------
if [ "$SKIP_EMBED" = false ]; then
  EMBEDDER_PY="$(ai_python "$AI_ROOT" embedder)"
  EMBED_ARGS=(--gallery-id "$GALLERY_ID")
  [ "$FORCE" = true ] && EMBED_ARGS+=(--force)
  echo "[embed] python -m embedder ${EMBED_ARGS[*]}"
  (cd "$AI_ROOT/embedder" && "$EMBEDDER_PY" -m embedder "${EMBED_ARGS[@]}")
fi

if [ "$SKIP_ANALYZE" = true ]; then
  echo "끝. 임베딩만 돌렸다."
  exit 0
fi

# --- 2. 점수 (AI repo score/, torch) → 3. 그룹·이름 (categorize/, Bedrock) ---------------------------
# 운영은 score Lambda 가 끝에서 categorize 를 EVENT 로 부른다. 여기서는 잡 없이 둘을 순서대로 직접 부른다 —
# categorize 는 --llm 으로 naming 까지 가지만, 잡이 없어 배정은 저장하지 않는다(로그·결과 payload 로만 확인).
SCORE_PY="$(ai_python "$AI_ROOT" score)"
SCORE_ARGS=(--gallery-id "$GALLERY_ID")
[ "$FORCE" = true ] && SCORE_ARGS+=(--force)
echo "[score] python -m score ${SCORE_ARGS[*]}"
"$SCORE_PY" -m score "${SCORE_ARGS[@]}"

echo "[categorize] python -m categorize --gallery-id $GALLERY_ID --llm"
"$SCORE_PY" -m categorize --gallery-id "$GALLERY_ID" --llm

echo "폴더 세트까지 가려면: 웹 \"AI 분석\" 버튼(잡) + scripts/local-worker.sh — 배정은 잡에 매달린다"
echo "끝. 확인: psql -h $DB_HOST -p $DB_PORT -U $DB_USER $DB_NAME -c \"SELECT count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded, count(model_version) AS scored, count(embed_group_id) AS categorized FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = $GALLERY_ID\""
