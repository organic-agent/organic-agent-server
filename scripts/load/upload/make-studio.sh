#!/usr/bin/env bash
# 재생용 dev 스튜디오 만들기 — dev에 스튜디오 워크스페이스가 없을 때 한 번. 부른 사용자가 OWNER가 된다. dev 쓰기(스튜디오·워크스페이스·멤버 1행씩).
# 대상: dev
# 바꾸는 것: dev DB(스튜디오·워크스페이스·멤버 1행씩)
#
#   scripts/load/upload/make-studio.sh dev --user-id U [--provider-id P] [--gallery-url load-test-studio]
#
# U = dev 사용자 id (dev 웹에 한 번 로그인한 계정 — scripts/load/upload/dev-ids.sh dev 또는 dev DB users 표).
# 끝에 replay.sh 에 넣을 인자(--workspace-id …)를 찍는다.
set -euo pipefail
case "${1:-}" in -h|--help) sed -n 2,9p "$0"; exit 0 ;; esac
source "$(dirname "$0")/../lib/report.sh"
cd "$(dirname "$0")/../../.."
env_take "${1:-}" && shift || true
env_require
[ "$ENV_TARGET" = "dev" ] || { echo "dev 전용이다(쓰기)." >&2; exit 1; }
source scripts/load/lib/venv.sh
exec "$REPLAY_PY" scripts/load/upload/replay.py studio --api "$API_BASE" --ssm-prefix "$SSM_PREFIX" "$@"
