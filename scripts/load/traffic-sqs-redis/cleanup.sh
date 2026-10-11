#!/usr/bin/env bash
# R-3 정리 — dev 쓰기. prepare.sh 가 만든 회차 갤러리(제목 "[부하 <label>] 사용자 …")를 지우고 VACUUM 한다.
#
#   scripts/load/traffic-sqs-redis/cleanup.sh dev --label R-3-a [--users]
#
# 복제 갤러리의 사진은 원본 S3 객체를 가리키므로(storage_key 에 #clone- 접미사) 버킷은 건드리지 않는다.
# --users 면 다른 갤러리 멤버가 아닌 테스트 사용자(provider_id load-user-*)도 지운다 — 평소에는 회차끼리 재사용하려고 남긴다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"
DROP_USERS="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --users) DROP_USERS="true" ;;
    -h|--help) sed -n 2,8p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다." >&2; exit 1; }
[[ "$REPORT_LABEL" =~ ^[A-Za-z0-9-]+$ ]] || { echo "--label 은 영문·숫자·하이픈만(제목 LIKE 패턴에 들어간다): $REPORT_LABEL" >&2; exit 1; }
report_connect
psql_write() {
  PGPASSWORD="$DB_PASSWORD" docker run --rm -i -e PGPASSWORD -e PGCLIENTENCODING=UTF8 pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}

PATTERN="[부하 $REPORT_LABEL] 사용자 %"
echo "지울 갤러리: $(psql_write -c "SELECT string_agg(id::text, ',' ORDER BY id) FROM galleries WHERE title LIKE '$PATTERN'")"
psql_write -c "DELETE FROM galleries WHERE title LIKE '$PATTERN'"
if [ "$DROP_USERS" = "true" ]; then
  # 다른 회차 갤러리에 아직 멤버로 남은 사용자는 지우지 않는다.
  psql_write -c "
    DELETE FROM users u
    WHERE u.provider = 'GOOGLE' AND u.provider_id LIKE 'load-user-%'
      AND NOT EXISTS (SELECT 1 FROM gallery_members gm WHERE gm.user_id = u.id)"
fi
psql_write -c "VACUUM (ANALYZE) photo_analysis" -c "VACUUM (ANALYZE) photos" -c "VACUUM (ANALYZE) photo_ratings" -c "VACUUM (ANALYZE) photo_selection_items"
echo "남은 것: 사진 $(psql_write -c "SELECT count(*) FROM photos") · 분석 $(psql_write -c "SELECT count(*) FROM photo_analysis") · 테스트 사용자 $(psql_write -c "SELECT count(*) FROM users WHERE provider_id LIKE 'load-user-%'")"
