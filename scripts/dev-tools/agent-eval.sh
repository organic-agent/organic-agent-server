#!/usr/bin/env bash
# 셀렉 도우미 판단기 측정 한 회차: 예측(wes 판단기 → Bedrock) → 채점(AI repo 채점기·로컬 채점 DB) → 회차 비교 표.
# 대상: local (채점 DB는 로컬 도커 agent-test-pg, 판단은 개발자 자격증명으로 Bedrock 서울)
# 바꾸는 것: 로컬 채점 DB(agent-test-pg)를 회차마다 템플릿으로 되돌림 · Bedrock 호출 비용(102문항 1회 ≈ $0.4)
#
#   scripts/dev-tools/agent-eval.sh sonnet46 global.anthropic.claude-sonnet-4-6 3
#   scripts/dev-tools/agent-eval.sh --table          # 지금까지 회차 표만
#
# 질문 세트·채점기·채점 DB는 AI repo 로컬 폴더(gitignore)에 있다 — AGENT_EVAL_DIR 로 바꿀 수 있다.
# 세트 파일은 AGENT_EVAL_SETS 로 고른다(예: questions_v3). 측정은 사람이 실행한다(.claude/rules/load-test.md와 같은 원칙).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVAL_DIR="${AGENT_EVAL_DIR:-$ROOT/../../organic-agent-ai/docs/experiments/photo-agent-eval-set-2026-10-09}"
PYTHON="${AGENT_EVAL_PYTHON:-$EVAL_DIR/../../../score/.venv/bin/python}"

if [[ "${1:-}" == "--table" ]]; then
  cd "$EVAL_DIR" && exec "$PYTHON" grade_wes.py --table
fi

LABEL="${1:?라벨을 준다 (예: sonnet46)}"
MODEL="${2:-global.anthropic.claude-sonnet-4-6}"
RUNS="${3:-3}"

echo "== 1. 예측: $LABEL (bedrock · $MODEL · ${RUNS}회) =="
(cd "$ROOT" && AGENT_EVAL=1 AGENT_EVAL_DIR="$EVAL_DIR" AGENT_EVAL_MODEL="$MODEL" \
  AGENT_EVAL_LABEL="$LABEL" AGENT_EVAL_RUNS="$RUNS" \
  ./gradlew :wes-api:test --rerun --tests '*AgentDecisionEvalUnitTest*' -q)

echo "== 2. 채점 =="
docker start agent-test-pg >/dev/null
until docker exec agent-test-pg pg_isready -U wes -q; do sleep 1; done
(cd "$EVAL_DIR" && ./reset_db.sh && "$PYTHON" grade_wes.py "$LABEL")

echo "== 3. 회차 비교 =="
(cd "$EVAL_DIR" && "$PYTHON" grade_wes.py --table)
