#!/usr/bin/env bash
# 셀렉 도우미 판단기 측정 한 회차: 예측(wes 판단기) → 채점(AI repo 채점기·테스트 DB) → 회차 비교 표.
#
#   scripts/agent-eval/run.sh <라벨> [엔진] [모델] [회수]
#   scripts/agent-eval/run.sh codex-terra openai codex-terra 3            # 구독 게이트웨이(scripts/llm), 비용 0
#   scripts/agent-eval/run.sh sonnet46 bedrock global.anthropic.claude-sonnet-4-6 3   # Bedrock, 돈이 든다
#   scripts/agent-eval/run.sh --table                                     # 지금까지 회차 표만
#
# 질문 세트·채점기·채점 DB는 AI repo 로컬 폴더(gitignore)에 있다 — AGENT_EVAL_DIR 로 바꿀 수 있다.
# 채점 DB는 도커 agent-test-pg(5433, dataset2 7,189장 템플릿)다. openai 엔진은 scripts/llm/.env 의 키를 읽는다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVAL_DIR="${AGENT_EVAL_DIR:-$ROOT/../../organic-agent-ai/docs/experiments/photo-agent-eval-set-2026-10-09}"
PYTHON="${AGENT_EVAL_PYTHON:-$EVAL_DIR/../../../score/.venv/bin/python}"

if [[ "${1:-}" == "--table" ]]; then
  cd "$EVAL_DIR" && exec "$PYTHON" grade_wes.py --table
fi

LABEL="${1:?라벨을 준다 (예: codex-terra)}"
ENGINE="${2:-openai}"
MODEL="${3:-codex-terra}"
RUNS="${4:-3}"

if [[ "$ENGINE" == "openai" && -z "${WES_LLM_API_KEY:-}" ]]; then
  WES_LLM_API_KEY="$(grep '^LITELLM_MASTER_KEY=' "$ROOT/scripts/llm/.env" | cut -d= -f2-)"
  export WES_LLM_API_KEY
fi

echo "== 1. 예측: $LABEL ($ENGINE · $MODEL · ${RUNS}회) =="
(cd "$ROOT" && AGENT_EVAL=1 AGENT_EVAL_DIR="$EVAL_DIR" AGENT_EVAL_ENGINE="$ENGINE" AGENT_EVAL_MODEL="$MODEL" \
  AGENT_EVAL_LABEL="$LABEL" AGENT_EVAL_RUNS="$RUNS" \
  ./gradlew :wes-api:test --rerun --tests '*AgentDecisionEvalUnitTest*' -q)

echo "== 2. 채점 =="
docker start agent-test-pg >/dev/null
until docker exec agent-test-pg pg_isready -U wes -q; do sleep 1; done
(cd "$EVAL_DIR" && ./reset_db.sh && "$PYTHON" grade_wes.py "$LABEL")

echo "== 3. 회차 비교 =="
(cd "$EVAL_DIR" && "$PYTHON" grade_wes.py --table)
