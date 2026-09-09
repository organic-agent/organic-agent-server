#!/usr/bin/env bash
# 손으로 테스트한 데이터를 지우고 처음 상태로 되돌린다.
#
# 사용법:
#   scripts/reset-test-data.sh                    로컬 DB의 갤러리·사진·폴더를 지운다(계정은 남긴다)
#   scripts/reset-test-data.sh remote             SSM 터널 너머의 RDS를 같은 방식으로 지운다
#   scripts/reset-test-data.sh remote --all       계정(users·studios·토큰)까지 지운다
#   scripts/reset-test-data.sh remote --with-s3   S3의 원본과 파생본도 함께 지운다
#   scripts/reset-test-data.sh local --with-s3    dev 버킷의 원본과 파생본도 함께 지운다
#   scripts/reset-test-data.sh remote --yes       확인 프롬프트를 건너뛴다
#
# **계정을 기본으로 남기는 이유**: users를 지우면 발급받은 액세스 토큰이 무효가 되어
# 매번 OAuth 로그인부터 다시 해야 한다. 반복해서 만지는 것은 갤러리와 사진이지 계정이 아니다.
#
# 스키마는 건드리지 않는다(flyway_schema_history 포함). 마이그레이션을 다시 돌릴 일이 있으면
# 로컬은 컨테이너 볼륨을 지우는 편이 빠르다:
#   docker compose -f docker-compose.local.yml down && rm -rf postgres-data-local
#
# 개발 단계 전용이다. 실사용자가 생기면 이 스크립트는 지운다.
set -euo pipefail

REGION="ap-northeast-2"
TARGET="local"
SCOPE="content"   # content | all
WITH_S3="false"
ASSUME_YES="false"

for arg in "$@"; do
  case "$arg" in
    local|remote) TARGET="$arg" ;;
    --all)        SCOPE="all" ;;
    --with-s3)    WITH_S3="true" ;;
    --yes|-y)     ASSUME_YES="true" ;;
    *) echo "알 수 없는 인자: $arg" >&2; exit 1 ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  # psql을 따로 깔지 않고 이미 쓰고 있는 postgres 이미지로 붙는다.
  echo "docker가 필요하다(psql을 컨테이너로 실행한다)." >&2
  exit 1
fi

# 환경은 버킷으로 갈린다(local은 dev 버킷, remote는 운영 버킷 — 아래 BUCKET_PARAM). 키 구조는
# 같으므로 원본(galleries/)과 임베더가 만드는 파생본(previews/galleries/)만 지운다.
S3_PREFIXES=(galleries/ previews/galleries/)

if [ "$TARGET" = "local" ]; then
  DB_HOST="host.docker.internal"; DB_PORT="5432"
  DB_NAME="wes"; DB_USER="wes"; DB_PASSWORD="wes"
  LABEL="로컬 (docker-compose)"
else
  # RDS는 비공개 서브넷에 있다. scripts/db-tunnel.sh 를 먼저 띄워 둘 것.
  if ! lsof -nP -iTCP:15432 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "15432 터널이 열려 있지 않다. 다른 탭에서 scripts/db-tunnel.sh 를 먼저 실행할 것." >&2
    exit 1
  fi
  DB_HOST="host.docker.internal"; DB_PORT="15432"
  DB_NAME="wes_db"; DB_USER="wes_admin"
  DB_PASSWORD=$(aws ssm get-parameter --region "$REGION" \
    --name /wes/prod/spring.datasource.password --with-decryption \
    --query 'Parameter.Value' --output text)
  LABEL="원격 RDS (터널 15432)"
fi

psql_run() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# 지우기 전에 무엇이 사라지는지 먼저 보여준다.
echo "대상: $LABEL / $DB_NAME"
echo
psql_run -c "
SELECT 'users' AS 테이블, count(*) FROM users
UNION ALL SELECT 'studios', count(*) FROM studios
UNION ALL SELECT 'galleries', count(*) FROM galleries
UNION ALL SELECT 'photos', count(*) FROM photos
UNION ALL SELECT 'concept_folders', count(*) FROM concept_folders;"

if [ "$SCOPE" = "all" ]; then
  echo "범위: 갤러리·사진·폴더·협업 세션 + 계정(users·studios·토큰)"
else
  echo "범위: 갤러리·사진·폴더·협업 세션 (계정은 남긴다)"
fi
# 확인을 받기 전에 S3 쪽도 규모를 보여준다. "몇 개가 지워지는지" 모르고 yes를 치게 하지 않는다.
if [ "$WITH_S3" = "true" ]; then
  # local은 dev 버킷(/wes/local), remote는 운영 버킷(/wes/prod). 로컬 pg를 비우면서 운영 객체를
  # 지우면 안 되므로 대상과 버킷을 같은 축으로 묶는다.
  if [ "$TARGET" = "local" ]; then BUCKET_PARAM=/wes/local/app.storage.bucket; else BUCKET_PARAM=/wes/prod/app.storage.bucket; fi
  BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$BUCKET_PARAM" \
    --query 'Parameter.Value' --output text)
  echo
  echo "S3: s3://$BUCKET 의 ${S3_PREFIXES[*]}"
  for prefix in "${S3_PREFIXES[@]}"; do
    # summarize는 마지막 두 줄에 개수와 합계 크기를 준다. 객체가 없으면 0으로 나온다.
    printf "  %-12s " "$prefix"
    aws s3 ls "s3://$BUCKET/$prefix" --region "$REGION" --recursive --summarize 2>/dev/null \
      | tail -2 | tr '\n' ' ' | sed 's/  */ /g'
    echo
  done
fi

if [ "$ASSUME_YES" != "true" ]; then
  echo
  read -r -p "정말 지울까? (yes 입력) " answer
  [ "$answer" = "yes" ] || { echo "취소했다."; exit 0; }
fi

# **참조하는 테이블을 하나도 빠뜨리면 안 된다.** V9이 외래키를 걸어둔 뒤로, 참조당하는 테이블만
# TRUNCATE 하면 Postgres가 명령 자체를 거부한다(ON DELETE CASCADE가 있어도 마찬가지다 --
# 그건 DELETE 얘기다). CASCADE 옵션을 붙이지 않는 이유는 그러면 여기 안 적은 테이블까지
# 조용히 함께 비워지기 때문이다. 마이그레이션으로 테이블이 늘면 이 목록도 함께 늘려야 한다.
#
# 자식부터 적는다. RESTART IDENTITY로 id도 1부터 다시 시작한다 -- 매번 같은 id로 테스트하면
# URL을 그대로 재사용할 수 있고, 어제 만든 갤러리 3번과 오늘 것이 헷갈리지 않는다.
TABLES="collab_session_photos, collab_photo_likes, collab_photo_comments, collab_participants, collab_sessions"
TABLES="$TABLES, admin_photo_replacement_uploads, admin_photo_revisions, admin_retouch_artifact_uploads, admin_ai_selection_jobs"
TABLES="$TABLES, ai_pair_verdicts, ai_recommendations, ai_selection_jobs, ai_concept_assignments, ai_analysis_jobs, photo_analysis"
TABLES="$TABLES, photo_category_assignments, detail_folders, concept_folders"
TABLES="$TABLES, admin_selection_revisions, photo_selection_items, photo_selections, photo_ratings, photo_comments"
TABLES="$TABLES, retouch_photos, retouch_rounds"
TABLES="$TABLES, photos"
TABLES="$TABLES, test_checkouts, studio_invites, gallery_invites, gallery_members, galleries"
TABLES="$TABLES, user_notifications, user_notification_settings, gallery_activity, workspace_activity"
TABLES="$TABLES, studio_retouch_capabilities"
# 관리자 쪽 갤러리·사진 참조 기록. FK가 없는 것도 있지만 가리키는 행이 사라지면 쓰레기라 함께 비운다.
TABLES="$TABLES, admin_processing_jobs, admin_trash_entries, admin_trash_batches, admin_child_trash_records, admin_entity_revisions, product_purge_claims"
# 선호 가중치 행. FK는 없지만 train_gallery_ids가 지워진 갤러리를 가리키게 되므로 함께 비운다.
TABLES="$TABLES, preference_models"
if [ "$SCOPE" = "all" ]; then
  TABLES="$TABLES, admin_notification_inbox_reads, admin_notification_inbox, admin_notification_outbox, admin_audit_logs"
  TABLES="$TABLES, admin_impersonation_sessions, admin_auth_events, admin_sessions, admin_accounts"
  TABLES="$TABLES, studios"
  # users를 지우려면 users를 참조하는 workspaces·workspace_members(studios·galleries의 부모)도 같이 비워야 한다.
  TABLES="$TABLES, workspace_members, workspaces"
  TABLES="$TABLES, refresh_tokens, oauth_states, users"
fi

psql_run -c "TRUNCATE TABLE $TABLES RESTART IDENTITY;"
echo "DB 정리 완료: $TABLES"

if [ "$WITH_S3" = "true" ]; then
  # 환경의 접두사만 지운다. 버킷을 통째로 비우면 다른 환경의 객체까지 함께 날아간다.
  for prefix in "${S3_PREFIXES[@]}"; do
    echo "S3 삭제: s3://$BUCKET/$prefix"
    aws s3 rm "s3://$BUCKET/$prefix" --region "$REGION" --recursive --only-show-errors
  done
  echo "S3 정리 완료"
elif [ "$TARGET" = "remote" ]; then
  # 사진 행을 지웠으니 S3 객체는 아무도 가리키지 않는다. 지우지 않으면 계속 쌓이고,
  # 나중에는 어느 것이 살아 있는 갤러리의 것인지 구분할 방법이 없다.
  echo
  echo "* S3 객체는 그대로 남아 있다(--with-s3 로 함께 지울 수 있다)."
fi

echo
echo "끝났다. 남은 상태:"
psql_run -c "
SELECT 'users' AS 테이블, count(*) FROM users
UNION ALL SELECT 'studios', count(*) FROM studios
UNION ALL SELECT 'galleries', count(*) FROM galleries
UNION ALL SELECT 'photos', count(*) FROM photos
UNION ALL SELECT 'concept_folders', count(*) FROM concept_folders;"
