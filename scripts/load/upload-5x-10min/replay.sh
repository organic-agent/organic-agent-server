#!/usr/bin/env bash
# 부하 재생 — 도착 시각표대로 dev 갤러리 N개에 실제 업로드 API 경로로 사진을 도착시키고, 끝나면 분석을 요청한다. dev 전용(쓰기).
# 계획: docs/plans/2026-10-08/upload-5x-10min/02-baseline.md 2.2(P1)·3장 회차.
#
#   scripts/load/upload-5x-10min/replay.sh --label B-1 --workspace-id W --user-id U                              # 단건
#   scripts/load/upload-5x-10min/replay.sh --label B-2-1 --count 5 --workspace-id W --user-id U                  # 5개 동시
#   scripts/load/upload-5x-10min/replay.sh --label Q-2 --count 5 --workspace-id W1,W2,W3,W4,W5 --user-id U       # 5개 동시, 스튜디오 5개(실제 작가 5명 모양)
#   scripts/load/upload-5x-10min/replay.sh --label B-3-1 --count 5 --stagger 60 --workspace-id W --user-id U     # 1분 간격
#   scripts/load/upload-5x-10min/replay.sh --label B-4 --count 5 --speed 2 --workspace-id W --user-id U          # 2배속(빠른 회선)
#   scripts/load/upload-5x-10min/replay.sh --label smoke --limit 200 --workspace-id W --user-id U                # 앞 200장만 (도구 확인)
#
#   --timetable  기본 docs/experiments/load-seed/48-timetable.json (scripts/load/upload-5x-10min/seed-dev.sh 로 만든다)
#   W·U = dev 스튜디오 워크스페이스와 그 멤버 사용자. JWT 는 /wes/dev/jwt.secret 로 직접 서명한다.
# 영향: dev DB에 갤러리·사진 행, dev 버킷에 사진 복사본, dev Lambda·GPU·Bedrock 과금. 운영은 건드리지 않는다.
# 중단: Ctrl+C (이미 나간 복사·호출은 끝까지 간다) → 남은 분석은 재생 갤러리를 관리자 삭제해 멈춘다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
case "${1:-}" in -h|--help) sed -n 2,16p "$0"; exit 0 ;; esac
TT="docs/experiments/load-seed/48-timetable.json"
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --timetable) TT="$2"; shift ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done
[ -f "$TT" ] || { echo "시각표가 없다: $TT — scripts/load/upload-5x-10min/seed-dev.sh --source 48 을 먼저" >&2; exit 1; }
source scripts/load/upload-5x-10min/venv.sh
exec "$REPLAY_PY" scripts/load/upload-5x-10min/replay.py run --timetable "$TT" "${ARGS[@]}"
