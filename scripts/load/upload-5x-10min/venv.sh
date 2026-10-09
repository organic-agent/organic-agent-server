#!/usr/bin/env bash
# 재생 스크립트용 파이썬 가상환경 — scripts/load/upload-5x-10min/.venv (gitignore). boto3 하나만 쓴다.
REPLAY_VENV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.venv"
# 설치가 중간에 실패한 venv 도 bin/python 은 있다 — boto3 를 실제로 불러 봐야 준비된 것이다.
if ! "$REPLAY_VENV/bin/python" -c "import boto3" >/dev/null 2>&1; then
  python3 -m venv --clear "$REPLAY_VENV" >&2
  "$REPLAY_VENV/bin/pip" install -q --upgrade pip boto3 >&2
fi
REPLAY_PY="$REPLAY_VENV/bin/python"
