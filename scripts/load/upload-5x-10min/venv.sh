#!/usr/bin/env bash
# 재생 스크립트용 파이썬 가상환경 — scripts/load/upload-5x-10min/.venv (gitignore). boto3 하나만 쓴다.
REPLAY_VENV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.venv"
if [ ! -x "$REPLAY_VENV/bin/python" ]; then
  python3 -m venv "$REPLAY_VENV" >&2
  "$REPLAY_VENV/bin/pip" install -q --upgrade pip boto3 >&2
fi
REPLAY_PY="$REPLAY_VENV/bin/python"
