#!/usr/bin/env bash
# 부하 스크립트용 파이썬 가상환경 — scripts/load/lib/.venv (gitignore). boto3 하나만 쓴다.
# 대상: local(이 노트북의 파이썬 가상환경)
# 바꾸는 것: scripts/load/lib/.venv (없거나 깨졌으면 새로 만듦)
#
#   source scripts/load/lib/venv.sh   # → $REPLAY_PY (boto3 가 든 파이썬)
REPLAY_VENV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.venv"
# 설치가 중간에 실패한 venv 도 bin/python 은 있다 — boto3 를 실제로 불러 봐야 준비된 것이다.
if ! "$REPLAY_VENV/bin/python" -c "import boto3" >/dev/null 2>&1; then
  python3 -m venv --clear "$REPLAY_VENV" >&2
  "$REPLAY_VENV/bin/pip" install -q --upgrade pip boto3 >&2
fi
REPLAY_PY="$REPLAY_VENV/bin/python"
