#!/usr/bin/env bash
# 부하 실측용 갤러리 복제 — 원본 갤러리의 사진 행과 분석 행(photo_analysis: 벡터·점수·백분위)을 새 갤러리 N개로 복제한다.
# 같은 S3 객체(storage_key·preview_key)를 가리키므로 업로드 없이 "이미 임베딩·점수까지 끝난 큰 갤러리"를 여러 개 만들 수 있다.
# 5갤러리 동시 categorize·물질화·스윕 부하(계획서 §8 Phase 4 W11)를 잴 때 쓴다.
#
#   scripts/load/clone-gallery-photos.sh [local|remote] --source G --count N [--strip embed|score|categorize] [--yes]
#
#   --source G      복제할 원본 갤러리 id (사진·분석 행이 있어야 한다)
#   --count N       만들 갤러리 수 (기본 5)
#   --strip STAGE   분석 행에서 그 단계부터의 결과를 비운다 — 파이프라인이 그 단계부터 다시 돌게 한다
#                   embed: 분석 행을 아예 복제하지 않는다(임베더부터)  score: clip·점수·백분위 비움  categorize: 백분위·그룹만 비움
#
# 새 갤러리는 원본과 같은 워크스페이스·작가에 "{제목} (부하 k)" 로 만들고 OPEN 상태다. 폴더·배정·잡은 복제하지 않는다.
# 지우려면 관리자 삭제 또는 reset-test-data.sh. 개발 단계 전용이다.
set -euo pipefail
REGION="ap-northeast-2"
TARGET="local"; SOURCE=""; COUNT=5; STRIP=""; ASSUME_YES="false"
while [ $# -gt 0 ]; do
  case "$1" in
    local|remote) TARGET="$1" ;;
    --source) SOURCE="$2"; shift ;;
    --count) COUNT="$2"; shift ;;
    --strip) STRIP="$2"; shift ;;
    --yes|-y) ASSUME_YES="true" ;;
    -h|--help) sed -n 2,14p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$SOURCE" ] || { echo "--source 갤러리 id가 필요하다 (--help)" >&2; exit 1; }
case "$STRIP" in ""|embed|score|categorize) ;; *) echo "--strip 은 embed|score|categorize 중 하나" >&2; exit 1 ;; esac
command -v docker >/dev/null 2>&1 || { echo "docker가 필요하다(psql을 컨테이너로 실행한다)." >&2; exit 1; }

if [ "$TARGET" = "local" ]; then
  DB_HOST="host.docker.internal"; DB_PORT="5432"; DB_NAME="wes"; DB_USER="wes"; DB_PASSWORD="wes"
else
  lsof -nP -iTCP:15432 -sTCP:LISTEN >/dev/null 2>&1 || { echo "15432 터널이 없다. scripts/db-tunnel.sh 를 먼저." >&2; exit 1; }
  DB_HOST="host.docker.internal"; DB_PORT="15432"; DB_NAME="wes_db"; DB_USER="wes_admin"
  DB_PASSWORD=$(aws ssm get-parameter --region "$REGION" --name /wes/prod/spring.datasource.password --with-decryption --query 'Parameter.Value' --output text)
fi
psql_run() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q "$@"
}

echo "원본 갤러리 $SOURCE ($TARGET/$DB_NAME):"
psql_run -c "
SELECT g.id, g.title, count(p.id) AS photos,
       count(*) FILTER (WHERE a.embedding IS NOT NULL) AS embedded,
       count(*) FILTER (WHERE a.clip_embedding IS NOT NULL) AS scored,
       count(*) FILTER (WHERE a.technical_pct IS NOT NULL) AS categorized
FROM galleries g LEFT JOIN photos p ON p.gallery_id = g.id AND p.deleted_at IS NULL
LEFT JOIN photo_analysis a ON a.photo_id = p.id
WHERE g.id = $SOURCE GROUP BY g.id"
if [ "$ASSUME_YES" != "true" ]; then
  read -r -p "위 갤러리를 ${COUNT}개로 복제한다(strip=${STRIP:-없음}). 계속? [y/N] " answer
  [ "$answer" = "y" ] || { echo "취소"; exit 0; }
fi

# 분석 행 복제 — strip 단계에 따라 컬럼을 비운다. embed 면 행 자체를 넣지 않는다(임베더가 UPSERT 로 만든다).
case "$STRIP" in
  embed) ANALYSIS_SQL="" ;;
  score) ANALYSIS_SQL="INSERT INTO photo_analysis (photo_id, embedding, embedding_model, face_boxes, created_at, updated_at)
      SELECT m.new_id, a.embedding, a.embedding_model, a.face_boxes, now(), now()
      FROM photo_analysis a JOIN mapping m ON m.old_id = a.photo_id WHERE a.embedding IS NOT NULL;" ;;
  categorize) ANALYSIS_SQL="INSERT INTO photo_analysis (photo_id, embedding, embedding_model, clip_embedding, subjects, sub_scores, face_boxes,
        pipeline_version, analyzed_at, created_at, updated_at)
      SELECT m.new_id, a.embedding, a.embedding_model, a.clip_embedding, a.subjects, a.sub_scores, a.face_boxes,
             a.pipeline_version, a.analyzed_at, now(), now()
      FROM photo_analysis a JOIN mapping m ON m.old_id = a.photo_id WHERE a.embedding IS NOT NULL;" ;;
  "") ANALYSIS_SQL="INSERT INTO photo_analysis (photo_id, embedding, embedding_model, clip_embedding, subjects, sub_scores, face_boxes,
        pipeline_version, analyzed_at, technical_pct, aesthetic_pct, burst_id, burst_rank, embed_group_id, error, created_at, updated_at)
      SELECT m.new_id, a.embedding, a.embedding_model, a.clip_embedding, a.subjects, a.sub_scores, a.face_boxes,
             a.pipeline_version, a.analyzed_at, a.technical_pct, a.aesthetic_pct, a.burst_id, a.burst_rank, a.embed_group_id, a.error, now(), now()
      FROM photo_analysis a JOIN mapping m ON m.old_id = a.photo_id;" ;;
esac

for k in $(seq 1 "$COUNT"); do
  psql_run <<SQL
BEGIN;
CREATE TEMP TABLE mapping (old_id bigint, new_id bigint) ON COMMIT DROP;
WITH src AS (SELECT * FROM galleries WHERE id = $SOURCE),
     g AS (
       INSERT INTO galleries (workspace_id, created_by_user_id, title, status, workflow_status, stage, shoot_type, max_selectable_photo_count,
                              max_retouch_round_count, version, created_at, updated_at)
       SELECT workspace_id, created_by_user_id, title || ' (부하 $k)', 'OPEN', workflow_status, stage, shoot_type, max_selectable_photo_count,
              max_retouch_round_count, 0, now(), now() FROM src RETURNING id
     ),
     p AS (
       INSERT INTO photos (gallery_id, storage_key, original_file_name, display_order, status, content_type, preview_key,
                           taken_at, camera_make, camera_model, exposure_time, f_number, iso, width, height, byte_size,
                           version, created_at, updated_at)
       SELECT g.id, ph.storage_key, ph.original_file_name, ph.display_order, 'UPLOADED', ph.content_type, ph.preview_key,
              ph.taken_at, ph.camera_make, ph.camera_model, ph.exposure_time, ph.f_number, ph.iso, ph.width, ph.height, ph.byte_size,
              0, now(), now()
       FROM photos ph, g WHERE ph.gallery_id = $SOURCE AND ph.deleted_at IS NULL AND ph.status = 'UPLOADED'
       RETURNING id, storage_key
     )
INSERT INTO mapping SELECT ph.id, p.id FROM p JOIN photos ph ON ph.storage_key = p.storage_key AND ph.gallery_id = $SOURCE;
$ANALYSIS_SQL
SELECT (SELECT max(gallery_id) FROM photos WHERE id IN (SELECT new_id FROM mapping)) AS new_gallery, count(*) AS photos FROM mapping;
COMMIT;
SQL
done
echo "끝. 새 갤러리는 galleries 에서 title LIKE '% (부하 %)' 로 찾는다. 파이프라인은 5초 스윕이 이어받는다(strip 한 단계부터)."
