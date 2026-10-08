#!/usr/bin/env bash
# 재생용 dev 스튜디오 만들기 — dev에 스튜디오 워크스페이스가 없을 때 한 번. 부른 사용자가 OWNER가 된다. dev 쓰기(스튜디오·워크스페이스·멤버 1행씩).
#
#   scripts/load/upload-5x-10min/make-studio.sh --user-id U [--provider-id P] [--gallery-url load-test-studio]
#
# U = dev 사용자 id (dev 웹에 한 번 로그인한 계정 — scripts/load/upload-5x-10min/dev-ids.sh dev 또는 dev DB users 표).
# 끝에 replay.sh 에 넣을 인자(--workspace-id …)를 찍는다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
case "${1:-}" in -h|--help) sed -n 2,7p "$0"; exit 0 ;; esac
source scripts/load/upload-5x-10min/venv.sh
exec "$REPLAY_PY" scripts/load/upload-5x-10min/replay.py studio "$@"
