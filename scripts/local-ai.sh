#!/usr/bin/env bash
# 로컬 AI 파이프라인을 한 번에 돌린다: 임베딩(DINOv3) → 분석(그룹·피사체·점수·클러스터, VLM은 Ollama).
#
# 운영에서 Lambda·배치가 하는 일을 노트북이 대신한다. 로컬 wes(local 프로필)에는 Lambda가 없어
# POST /embeddings/run 이 503으로 끝나므로, 사진을 올린 뒤 이 스크립트를 부른다.
# (추천 draft 단계는 추천 기능 재설계로 제거했다 — docs/plans/ai-folder-structure.md)
#
#   scripts/local-ai.sh <galleryId> [--force] [--no-vlm]
#                       [--skip-embed] [--skip-analyze] [--only-embed]
#
#   --force         이미 벡터가 있는 사진도 다시 계산한다 (임베더 --force)
#   --no-vlm        분석에서 VLM 태그를 뺀다. Ollama를 띄우지 않는다
#   --only-embed    = --skip-analyze
#
# 전제: docker(pg), python3, 호스트 Ollama(VLM, --no-vlm이면 불필요), AWS 자격증명(dev 버킷 + /wes/local/ 읽기).
# DB는 docker-compose.local.yml의 pg(localhost:5432 wes/wes/wes)가 기본이고 DB_* 환경변수로 덮어쓴다.
# S3_BUCKET은 환경변수 → /wes/local/app.storage.bucket(인프라 apply가 기록) 순으로 정한다.
# 운영 RDS를 가리키게 하지 마라 — 미확정 스키마 시험은 부술 수 있는 DB에서만 한다.
set -euo pipefail

WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"
COMPOSE=(docker compose -f "$WES_ROOT/docker-compose.local.yml")
REGION="${AWS_REGION:-ap-northeast-2}"
BUCKET_PARAM="/wes/local/app.storage.bucket"
VLM_MODEL="${VLM_MODEL:-gemma3:12b}"   # AI repo config.py의 vlm_model과 같아야 한다
VLM_HOST="http://127.0.0.1:11434"

GALLERY_ID=""
FORCE=false; NO_VLM=false
SKIP_EMBED=false; SKIP_ANALYZE=false

while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=true ;;
    --no-vlm) NO_VLM=true ;;
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

# --- AI repo CLI ---------------------------------------------------------------------------------
if [ "$SKIP_ANALYZE" = true ]; then
  echo "끝. 임베딩만 돌렸다."
  exit 0
fi

if [ ! -d "$AI_ROOT/photoselect" ]; then
  echo "AI repo를 찾지 못했다: $AI_ROOT (AI_ROOT 환경변수로 지정)" >&2
  exit 1
fi
AI_PY="$AI_ROOT/photoselect/scripts/spike/.venv/bin/python"
[ -x "$AI_PY" ] || AI_PY="python3"

# AI repo의 --db 스위치(DbStore)는 AI repo 쪽 작업이다. 없으면 여기서 멈춘다 —
# 로컬 파일 모드로 돌리면 결과가 out/에만 남고 wes API가 읽는 photo_analysis에는 아무것도 안 들어간다.
if ! (cd "$AI_ROOT" && "$AI_PY" -m photoselect analyze --help 2>/dev/null | grep -q -- '--db'); then
  echo "AI repo의 'photoselect analyze --db'가 아직 없다(DbStore 미구현). 임베딩까지만 끝났다." >&2
  echo "  $AI_ROOT/photoselect — e2e-test-plan.md 1단계(DbStore·--db)가 끝나면 다시 부른다." >&2
  exit 2
fi

# --- 2. 분석 (VLM은 Ollama) ----------------------------------------------------------------------
if [ "$SKIP_ANALYZE" = false ]; then
  ANALYZE_ARGS=(--gallery "$GALLERY_ID" --db)
  [ "$FORCE" = true ] && ANALYZE_ARGS+=(--force)
  if [ "$NO_VLM" = true ]; then
    ANALYZE_ARGS+=(--no-vlm)
  else
    # 호스트 Ollama(맥은 GPU/MPS를 잡는다 -- Docker Ollama는 못 잡아 쓰지 않는다). 안 떠 있으면 띄운다.
    if ! curl -sf "$VLM_HOST/api/tags" >/dev/null; then
      command -v ollama >/dev/null || { echo "ollama가 없다. brew install ollama 또는 --no-vlm" >&2; exit 1; }
      echo "[vlm] ollama serve 기동 (로그: /tmp/ollama-local.log)"
      nohup ollama serve >/tmp/ollama-local.log 2>&1 &
      for _ in $(seq 1 30); do curl -sf "$VLM_HOST/api/tags" >/dev/null && break; sleep 1; done
    fi
    if ! ollama list 2>/dev/null | grep -q "^$VLM_MODEL"; then
      echo "[vlm] 모델 pull: $VLM_MODEL (첫 실행은 수 GB)"
      ollama pull "$VLM_MODEL"
    fi
  fi
  echo "[analyze] python -m photoselect analyze ${ANALYZE_ARGS[*]}"
  (cd "$AI_ROOT" && "$AI_PY" -m photoselect analyze "${ANALYZE_ARGS[@]}")
fi

echo "확인(API): POST /api/v1/galleries/$GALLERY_ID/folder-groups/ai (작가 토큰) — 배정이 있으면 AI 폴더 세트가 생긴다"
echo "끝. 확인: psql -h $DB_HOST -p $DB_PORT -U $DB_USER $DB_NAME -c \"SELECT count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded, count(model_version) AS analyzed FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = $GALLERY_ID\""
