#!/usr/bin/env bash
# 로컬 AI 파이프라인을 잡 없이 한 번에 돌린다: embedder → score → categorize (scripts/lambda/*.sh 를 갤러리 전체로 순서대로).
#
# 정식 경로는 웹 버튼이다 — 로컬 wes(local 프로필)의 analysis 도메인이 업로드된 사진을 50장씩 embedder.sh 에 배정하고,
# 점수가 다 차면 categorize.sh 를 부른 뒤 폴더를 만든다. 이 스크립트는 그 경로 없이 갤러리 하나를 CLI 로 끝까지 밀어 보는
# 지름길이라 잡이 없고, 따라서 배정(concept_assignments)·폴더는 저장되지 않는다. 폴더 세트가 필요하면 웹 버튼을 쓴다.
# 재계산이 필요하면 관리자 재처리(분석 리셋)로 photo_analysis 행을 지운 뒤 돌린다 — --force 는 없다.
#
#   scripts/local-ai.sh <galleryId> [--skip-embed] [--skip-analyze]
#
# 전제: docker(pg), python3(3.12), AWS 자격증명(로컬 버킷 + /wes/local/ 읽기 + Bedrock — categorize 의 naming).
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAMBDA="$WES_ROOT/scripts/lambda"

GALLERY_ID=""; SKIP_EMBED=false; SKIP_ANALYZE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-embed) SKIP_EMBED=true ;;
    --skip-analyze) SKIP_ANALYZE=true ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    -*) echo "모르는 옵션: $1" >&2; exit 1 ;;
    *) GALLERY_ID="$1" ;;
  esac
  shift
done
[ -n "$GALLERY_ID" ] || { echo "사용법: scripts/local-ai.sh <galleryId> [옵션]  (--help)" >&2; exit 1; }

if [ "$SKIP_EMBED" = false ]; then
  "$LAMBDA/embedder.sh" --gallery-id "$GALLERY_ID"
fi
if [ "$SKIP_ANALYZE" = true ]; then
  echo "끝. 임베딩만 돌렸다."
  exit 0
fi
"$LAMBDA/score.sh" --gallery-id "$GALLERY_ID"
"$LAMBDA/categorize.sh" --gallery-id "$GALLERY_ID"

echo "끝. 확인: psql -h \${DB_HOST:-localhost} -p \${DB_PORT:-5432} -U \${DB_USER:-wes} \${DB_NAME:-wes} -c \"SELECT count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded, count(pipeline_version) AS scored, count(embed_group_id) AS categorized FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = $GALLERY_ID\""
