#!/usr/bin/env bash
# codex-lb → LiteLLM 경로 확인. 모델 목록 → codex-lb 직접 호출 → LiteLLM 경유 호출.
set -euo pipefail
cd "$(dirname "$0")"
source .env
MODEL="${1:-codex-terra}"

echo "== 1. codex-lb 모델 목록 =="
curl -sf http://localhost:2455/v1/models -H "Authorization: Bearer $CODEX_LB_API_KEY" \
  | python3 -c 'import sys,json; [print(" -", m["id"]) for m in json.load(sys.stdin)["data"]]'

echo; echo "== 2. LiteLLM 경유 ($MODEL) =="
curl -sf http://localhost:4100/v1/chat/completions \
  -H "Authorization: Bearer $LITELLM_MASTER_KEY" -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["choices"][0]["message"]["content"])'

echo; echo "통과. wes 로컬: WES_LLM_API_KEY=\$LITELLM_MASTER_KEY, app.llm.provider=openai"
