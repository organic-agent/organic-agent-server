#!/usr/bin/env bash
# 로컬 GPU 워커 대역 — 운영의 EC2 score 워커 한 대에 해당한다. wes(local 프로필, app.analysis.gpu.enabled=true)의
# GpuController 가 "켜기" 자리에서 이 스크립트를 한 번 띄운다(LocalProcessScoreWorkerPool).
#
#   scripts/gpu/score-worker.sh
#
# AI repo `score worker --gpu --once --no-idle-stop`: photo_analysis 에서 벡터는 있고 점수가 없는 사진을 32장씩 SKIP LOCKED 로 집어
# CLIP·점수·피사체를 적고, 쌓인 것을 다 처리하면 끝난다(운영 워커는 유휴 30초 뒤 자기 인스턴스를 정지한다 — 로컬은 그냥 종료).
# 노트북에 CUDA 가 없으면 CPU 로 돈다. 추가 인자는 SCORE_WORKER_ARGS 로 넘긴다.
set -euo pipefail
WES_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI_ROOT="${AI_ROOT:-$WES_ROOT/../../organic-agent-ai}"

# shellcheck source=scripts/lib/ai-env.sh
. "$WES_ROOT/scripts/lib/ai-env.sh"
# shellcheck source=scripts/lib/ai-venv.sh
. "$WES_ROOT/scripts/lib/ai-venv.sh"
ai_env "$WES_ROOT"

PY="$(ai_python "$AI_ROOT" score)"
# shellcheck disable=SC2206
ARGS=(worker --gpu --once --no-idle-stop ${SCORE_WORKER_ARGS:-})
echo "[score-worker] python -m score ${ARGS[*]}  db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME bucket=$S3_BUCKET"
exec "$PY" -m score "${ARGS[@]}"
