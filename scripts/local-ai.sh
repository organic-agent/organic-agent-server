#!/usr/bin/env bash
# 로컬 AI 파이프라인을 잡 없이 한 번에 돌린다: embedder → score → categorize (scripts/lambda/*.sh 를 순서대로).
#
# 정식 경로는 웹 버튼이다 — 로컬 wes(local 프로필)의 analysis 도메인이 "AI 분석"에서 scripts/lambda/<단계>.sh 를
# 운영의 Lambda EVENT 자리에서 띄운다. 이 스크립트는 그 경로 없이 갤러리 하나를 CLI 로 끝까지 밀어 보는 지름길이라
# 잡이 없고, 따라서 배정(ai_concept_assignments)은 저장되지 않는다. 폴더 세트가 필요하면 웹 버튼을 쓴다.
#
#   scripts/local-ai.sh <galleryId> [--force] [--skip-embed] [--skip-analyze] [--only-embed]
#
#   --force         이미 벡터·점수가 있는 사진도 다시 계산한다 (embedder --force, score --force)
#   --only-embed    = --skip-analyze
#
# 전제: docker(pg), python3(3.12), AWS 자격증명(dev 버킷 + /wes/local/ 읽기 + Bedrock — categorize 의 naming).
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAMBDA="$WES_ROOT/scripts/lambda"

GALLERY_ID=""; FORCE=false; SKIP_EMBED=false; SKIP_ANALYZE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=true ;;
    --skip-embed) SKIP_EMBED=true ;;
    --skip-analyze|--only-embed) SKIP_ANALYZE=true ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    -*) echo "모르는 옵션: $1" >&2; exit 1 ;;
    *) GALLERY_ID="$1" ;;
  esac
  shift
done
[ -n "$GALLERY_ID" ] || { echo "사용법: scripts/local-ai.sh <galleryId> [옵션]  (--help)" >&2; exit 1; }

FORCE_ARG=(); [ "$FORCE" = true ] && FORCE_ARG=(--force)

if [ "$SKIP_EMBED" = false ]; then
  "$LAMBDA/embedder.sh" --gallery-id "$GALLERY_ID" "${FORCE_ARG[@]}"
fi
if [ "$SKIP_ANALYZE" = true ]; then
  echo "끝. 임베딩만 돌렸다."
  exit 0
fi
# 잡이 없으니 score 는 categorize 를 체인으로 부르지 않는다(--job-id 가 있을 때만). 여기서 직접 잇는다.
CATEGORIZE_COMMAND="" "$LAMBDA/score.sh" --gallery-id "$GALLERY_ID" "${FORCE_ARG[@]}"
"$LAMBDA/categorize.sh" --gallery-id "$GALLERY_ID"

echo "끝. 확인: psql -h \${DB_HOST:-localhost} -p \${DB_PORT:-5432} -U \${DB_USER:-wes} \${DB_NAME:-wes} -c \"SELECT count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded, count(model_version) AS scored, count(embed_group_id) AS categorized FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = $GALLERY_ID\""
