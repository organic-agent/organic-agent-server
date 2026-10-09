#!/usr/bin/env bash
# 재생에 넣을 dev 스튜디오 워크스페이스와 멤버 사용자 찾기 — replay.sh 의 --workspace-id · --user-id · --role · --provider-id. 읽기 전용.
#
#   scripts/load/upload-5x-10min/dev-ids.sh dev                 # 준비: 다른 터미널에서 scripts/db-tunnel.sh dev
set -euo pipefail
source "$(dirname "$0")/../lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}
case "${1:-}" in -h|--help) sed -n 2,4p "$0"; exit 0 ;; esac
[ "$REPORT_TARGET" = dev ] || { echo "dev 대상만 — scripts/load/upload-5x-10min/dev-ids.sh dev" >&2; exit 1; }
report_connect
report_begin "재생용 dev 스튜디오 워크스페이스·멤버" "삭제·정지되지 않은 STUDIO 워크스페이스"
report_section "후보" "스튜디오 갤러리는 사진 수 한도가 없다 — 이 중 하나를 고른다"
report_sql -c "
  SELECT w.id AS workspace_id, w.name AS 워크스페이스, u.id AS user_id, u.email AS 이메일, m.role AS 멤버역할,
         u.role AS 사용자역할, u.provider_id AS provider_id,
         (SELECT count(*) FROM galleries g WHERE g.workspace_id = w.id AND g.deleted_at IS NULL) AS 갤러리
  FROM workspaces w
  JOIN workspace_members m ON m.workspace_id = w.id AND m.deleted_at IS NULL
  JOIN users u ON u.id = m.user_id AND u.deleted_at IS NULL AND u.suspended_at IS NULL
  WHERE w.type = 'STUDIO' AND w.deleted_at IS NULL
  ORDER BY w.id, m.role DESC, u.id"
report_note "고른 줄로: scripts/load/upload-5x-10min/replay.sh --label smoke --limit 200 --workspace-id <workspace_id> --user-id <user_id> --role <사용자역할> --provider-id <provider_id>"
report_end
