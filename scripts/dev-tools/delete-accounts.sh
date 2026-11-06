#!/usr/bin/env bash
# 특정 OAuth 계정과 그 계정에 딸린 것만 지운다 — 다른 사용자·관리자 계정·스키마는 그대로 둔다.
# 전체 초기화는 reset-test-data.sh, 이 스크립트는 "내 계정만 새로 만들어 업로드 플로우를 다시 밟고 싶을 때"다.
# 대상: local | dev | prod (prod 는 사용자가 직접)
# 바꾸는 것: DB(대상 계정과 딸린 워크스페이스·갤러리·사진·토큰) · --with-s3 면 그 갤러리의 S3 원본·파생본
#
# 사용법:
#   scripts/dev-tools/delete-accounts.sh dev   --email a@x.com [--email b@y.com ...]             DB만
#   scripts/dev-tools/delete-accounts.sh dev   --email a@x.com --with-s3                          S3 원본·파생본까지
#   scripts/dev-tools/delete-accounts.sh local --email a@x.com --with-s3                          로컬 pg + 로컬 버킷
#   scripts/dev-tools/delete-accounts.sh dev   --email a@x.com --yes                              확인 프롬프트 생략
#   scripts/dev-tools/delete-accounts.sh prod  --email a@x.com                                    운영 — 사용자가 직접 실행한다(hook 이 막는다)
#   환경 인자는 필수다(기본값 없음).
#
# 같은 이메일이 여러 provider(KAKAO·NAVER·GOOGLE)로 있으면 전부 대상이다.
#
# 지우는 범위(FK ON DELETE CASCADE가 나머지를 끌고 간다 — 규칙은 V5·V6·V9):
#   users                 → 개인 워크스페이스(personal_owner_user_id) → 그 갤러리 → 사진 → 분석·폴더·셀렉·보정·협업·댓글·관리자 기록
#   STUDIO 워크스페이스    → 멤버가 전부 대상 계정뿐인 것만. 다른 사람이 함께 있는 스튜디오는 남기고 대상 계정의 멤버십만 뺀다
#   FK 없이 user_id만 든 것 → refresh_tokens · gallery_members(남의 갤러리에 초대된 멤버십)는 직접 지운다
#   S3                    → 지워지는 갤러리의 galleries/{id}/ 와 previews/galleries/{id}/ (--with-s3)
#
# 개발 단계 전용이다. 실사용자가 생기면 이 스크립트는 지운다.
set -euo pipefail
source "$(dirname "$0")/../lib/env.sh"

WITH_S3="false"
ASSUME_YES="false"
EMAILS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --email)      shift; EMAILS+=("$1") ;;
    --with-s3)    WITH_S3="true" ;;
    --yes|-y)     ASSUME_YES="true" ;;
    *) env_take "$1" || { echo "알 수 없는 인자: $1" >&2; exit 1; } ;;
  esac
  shift
done
env_require

if [ ${#EMAILS[@]} -eq 0 ]; then
  echo "--email 을 하나 이상 줘야 한다." >&2
  exit 1
fi

# psql 은 컨테이너로 돈다. dev·prod 는 터널이 먼저 떠 있어야 한다. 버킷은 대상 DB 와 같은 환경(BUCKET_PARAM).
env_db_connect
if [ "$ENV_TARGET" = "local" ]; then LABEL="로컬 (docker-compose)"; else LABEL="$ENV_NAME RDS (터널 $TUNNEL_PORT)"; fi

psql_run() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# 이메일 목록을 SQL 리터럴로. 작은따옴표는 이메일에 못 들어가지만 그래도 이스케이프한다.
EMAIL_LIST=""
for e in "${EMAILS[@]}"; do
  EMAIL_LIST="${EMAIL_LIST:+$EMAIL_LIST, }'${e//\'/\'\'}'"
done

# 대상 집합을 정하는 CTE. 미리보기와 삭제가 같은 정의를 쓴다 — 보여준 것과 지우는 것이 어긋나면 안 된다.
SCOPE_CTE="
WITH target_users AS (
  SELECT id FROM users WHERE email IN ($EMAIL_LIST)
),
target_workspaces AS (
  SELECT w.id FROM workspaces w WHERE w.personal_owner_user_id IN (SELECT id FROM target_users)
  UNION
  SELECT w.id FROM workspaces w
  WHERE w.type = 'STUDIO'
    AND EXISTS (SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id IN (SELECT id FROM target_users))
    AND NOT EXISTS (SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id NOT IN (SELECT id FROM target_users))
),
target_galleries AS (
  SELECT g.id FROM galleries g WHERE g.workspace_id IN (SELECT id FROM target_workspaces)
)"

echo "대상: $LABEL / $DB_NAME"
echo "계정: ${EMAILS[*]}"
echo
psql_run -c "$SCOPE_CTE
SELECT 'users' AS 대상, count(*)::text AS 값, string_agg(u.id::text || ' ' || u.provider || ' ' || u.email, ' · ' ORDER BY u.id) AS 내역
  FROM users u WHERE u.id IN (SELECT id FROM target_users)
UNION ALL SELECT 'workspaces', count(*)::text, string_agg(w.id::text || ' ' || w.type || ' ' || w.name, ' · ' ORDER BY w.id)
  FROM workspaces w WHERE w.id IN (SELECT id FROM target_workspaces)
UNION ALL SELECT 'studios', count(*)::text, string_agg(s.name, ' · ')
  FROM studios s WHERE s.workspace_id IN (SELECT id FROM target_workspaces)
UNION ALL SELECT 'galleries', count(*)::text, string_agg(g.id::text || ' ' || g.title, ' · ' ORDER BY g.id)
  FROM galleries g WHERE g.id IN (SELECT id FROM target_galleries)
UNION ALL SELECT 'photos', count(*)::text, NULL
  FROM photos p WHERE p.gallery_id IN (SELECT id FROM target_galleries)
UNION ALL SELECT 'gallery_members(남의 갤러리)', count(*)::text, string_agg('gallery ' || gm.gallery_id::text, ' · ')
  FROM gallery_members gm WHERE gm.user_id IN (SELECT id FROM target_users) AND gm.gallery_id NOT IN (SELECT id FROM target_galleries)
UNION ALL SELECT 'studio 멤버십만 제거', count(*)::text, string_agg('workspace ' || m.workspace_id::text, ' · ')
  FROM workspace_members m WHERE m.user_id IN (SELECT id FROM target_users) AND m.workspace_id NOT IN (SELECT id FROM target_workspaces);"

GALLERY_IDS=$(psql_run -At -c "$SCOPE_CTE SELECT string_agg(id::text, ' ' ORDER BY id) FROM target_galleries;")
USER_COUNT=$(psql_run -At -c "$SCOPE_CTE SELECT count(*) FROM target_users;")
if [ "$USER_COUNT" = "0" ]; then
  echo "해당 이메일의 계정이 없다. 아무것도 하지 않는다."
  exit 0
fi

if [ "$WITH_S3" = "true" ]; then
  BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$BUCKET_PARAM" --query 'Parameter.Value' --output text)
  echo
  echo "S3: s3://$BUCKET"
  for gid in $GALLERY_IDS; do
    for prefix in "galleries/$gid/" "previews/galleries/$gid/"; do
      printf "  %-28s " "$prefix"
      aws s3 ls "s3://$BUCKET/$prefix" --region "$REGION" --recursive --summarize 2>/dev/null \
        | tail -2 | tr '\n' ' ' | sed 's/  */ /g'
      echo
    done
  done
fi

if [ "$ASSUME_YES" != "true" ]; then
  echo
  read -r -p "정말 지울까? (yes 입력) " answer
  [ "$answer" = "yes" ] || { echo "취소했다."; exit 0; }
fi

# 한 트랜잭션. 순서: FK 없는 참조(토큰·멤버십) → 워크스페이스(갤러리·사진·스튜디오 CASCADE) → 사용자(개인 워크스페이스·알림 CASCADE).
psql_run <<SQL
BEGIN;
$SCOPE_CTE
, del_tokens AS (
  DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM target_users) RETURNING 1
), del_members AS (
  DELETE FROM gallery_members WHERE user_id IN (SELECT id FROM target_users) RETURNING 1
), del_ws_members AS (
  DELETE FROM workspace_members WHERE user_id IN (SELECT id FROM target_users) RETURNING 1
), del_workspaces AS (
  DELETE FROM workspaces WHERE id IN (SELECT id FROM target_workspaces) RETURNING 1
), del_users AS (
  DELETE FROM users WHERE id IN (SELECT id FROM target_users) RETURNING 1
)
SELECT (SELECT count(*) FROM del_tokens) AS refresh_tokens,
       (SELECT count(*) FROM del_members) AS gallery_members,
       (SELECT count(*) FROM del_ws_members) AS workspace_members,
       (SELECT count(*) FROM del_workspaces) AS workspaces,
       (SELECT count(*) FROM del_users) AS users;
COMMIT;
SQL
echo "DB 정리 완료"

if [ "$WITH_S3" = "true" ]; then
  for gid in $GALLERY_IDS; do
    for prefix in "galleries/$gid/" "previews/galleries/$gid/"; do
      echo "S3 삭제: s3://$BUCKET/$prefix"
      aws s3 rm "s3://$BUCKET/$prefix" --region "$REGION" --recursive --only-show-errors
    done
  done
  echo "S3 정리 완료"
elif [ -n "$GALLERY_IDS" ]; then
  echo
  echo "* 갤러리 $GALLERY_IDS 의 S3 객체는 그대로 남아 있다(--with-s3 로 함께 지울 수 있다)."
fi

echo
echo "끝났다. 남은 상태:"
psql_run -c "
SELECT 'users' AS 테이블, count(*) FROM users
UNION ALL SELECT 'workspaces', count(*) FROM workspaces
UNION ALL SELECT 'galleries', count(*) FROM galleries
UNION ALL SELECT 'photos', count(*) FROM photos;"
