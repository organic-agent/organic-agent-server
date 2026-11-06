#!/usr/bin/env bash
# 복제 갤러리의 미리보기를 갤러리마다 제 경로로 옮긴다 — dev 쓰기.
# 대상: dev
# 바꾸는 것: dev 버킷 미리보기 사본 · dev photos.preview_key (--check 면 없음)
#
#   scripts/load/traffic/relocate-previews.sh dev --galleries 1579-1728            # 옮기고 검증
#   scripts/load/traffic/relocate-previews.sh dev --galleries 1579-1728 --check    # 검증만(쓰기 없음)
#
# 왜: prepare.sh 의 복제 갤러리는 원본 갤러리의 미리보기(previews/galleries/<원본>/…)를 그대로 가리킨다.
#   ① CDN 쿠키는 자기 갤러리 경로(previews/galleries/<id>/)만 연다 — CDN 회차에서 사진이 거부된다.
#   ② 갤러리 150개가 같은 사진 499장을 나눠 써서 캐시 적중이 실제(서로 다른 7.5만 장)보다 부풀려진다.
# 하는 일: 자기 경로가 아닌 미리보기를 previews/galleries/<id>/<파일명> 으로 버킷 안 복사(CopyObject) → photos.preview_key 갱신
#   → 검증(키가 전부 제 경로 · 객체 수 = 사진 수). 다시 돌려도 이미 옮긴 행은 건너뛴다. 원본(storage_key)은 건드리지 않는다.
# 영향: dev 버킷에 미리보기 사본(갤러리 수 × 사진 수), dev photos.preview_key. 운영은 건드리지 않는다.
# 되돌리기·정리: 갤러리를 cleanup.sh 로 지울 때 사본도 지운다 —
#   for g in $(seq A B); do aws s3 rm "s3://wes-dev-photos-<계정>/previews/galleries/$g/" --recursive --quiet; done
set -euo pipefail
source "$(dirname "$0")/../lib/report.sh"
cd "$(dirname "$0")/../../.."
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"
RANGE=""; CHECK_ONLY="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --galleries) RANGE="$2"; shift ;;
    --check) CHECK_ONLY="true" ;;
    -h|--help) sed -n 2,16p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[[ "$RANGE" =~ ^([0-9]+)-([0-9]+)$ ]] || { echo "--galleries 는 A-B 범위(예: 1579-1728)" >&2; exit 1; }
FROM="${BASH_REMATCH[1]}"; TO="${BASH_REMATCH[2]}"
[ "$FROM" -le "$TO" ] || { echo "--galleries 범위가 거꾸로다: $RANGE" >&2; exit 1; }

report_connect
BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
[[ "$BUCKET" == "$RESOURCE_PREFIX"-* ]] || { echo "$ENV_NAME 버킷이 아니다: $BUCKET" >&2; exit 1; }

psql_write() {
  PGPASSWORD="$DB_PASSWORD" docker run --rm -i -e PGPASSWORD -e PGCLIENTENCODING=UTF8 pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}

# 자기 갤러리 경로가 아닌 미리보기. 새 키 = previews/galleries/<id>/<파일명>.
MISPLACED="gallery_id BETWEEN $FROM AND $TO AND deleted_at IS NULL AND preview_key IS NOT NULL
  AND preview_key NOT LIKE 'previews/galleries/' || gallery_id || '/%'"
NEW_KEY="'previews/galleries/' || gallery_id || '/' || regexp_replace(preview_key, '^.*/', '')"

report_begin "복제 갤러리 미리보기 제 경로로 옮기기" "dev 갤러리 $FROM~$TO · 버킷 $BUCKET"

if [ "$CHECK_ONLY" = "false" ]; then
  # 복사 전에 새 키가 겹치는지 본다 — 같은 갤러리에 파일명이 같은 미리보기가 둘이거나, 새 키를 이미 다른 사진이 쓰면
  # 한 사진의 미리보기가 다른 사진으로 덮인다. 겹치면 복사·DB 갱신 없이 멈춘다.
  MOVES="SELECT id, $NEW_KEY AS k FROM photos WHERE $MISPLACED"
  DUP=$(psql_write -c "SELECT count(*) FROM (SELECT k FROM ($MOVES) m GROUP BY k HAVING count(*) > 1) d")
  TAKEN=$(psql_write -c "SELECT count(*) FROM photos p JOIN ($MOVES) m ON p.preview_key = m.k AND p.id <> m.id")
  if [ "$DUP" != "0" ] || [ "$TAKEN" != "0" ]; then
    echo "새 키가 겹친다 — 같은 새 키를 받는 키 ${DUP}개 · 이미 다른 사진이 쓰는 새 키 ${TAKEN}개. 복사하지 않고 멈춘다." >&2
    exit 1
  fi
  PAIRS_FILE=$(mktemp -t relocate-pairs.XXXXXX)
  trap 'rm -f "$PAIRS_FILE"' EXIT
  psql_write -F $'\t' -c "SELECT preview_key, $NEW_KEY FROM photos WHERE $MISPLACED" > "$PAIRS_FILE"
  TOTAL=$(wc -l < "$PAIRS_FILE" | tr -d ' ')
  report_section "1. 복사" "옮길 미리보기 ${TOTAL}개"
  if [ "$TOTAL" -gt 0 ]; then
    source scripts/load/lib/venv.sh
    # 하나라도 실패하면 DB 를 바꾸지 않고 멈춘다 — 다시 돌리면 같은 목록을 다시 복사한다(덮어쓰기라 안전).
    "$REPLAY_PY" - "$BUCKET" "$REGION" "$PAIRS_FILE" <<'EOF'
import sys, time
from concurrent.futures import ThreadPoolExecutor
import boto3
from botocore.config import Config

bucket, region, pairs_file = sys.argv[1:4]
pairs = [line.rstrip("\n").split("\t") for line in open(pairs_file) if line.strip()]
s3 = boto3.client("s3", region_name=region,
                  config=Config(max_pool_connections=64, retries={"max_attempts": 6, "mode": "standard"}))

def copy(pair):
    src, dst = pair
    try:
        s3.copy_object(Bucket=bucket, Key=dst, CopySource={"Bucket": bucket, "Key": src})
        return None
    except Exception as e:  # noqa: BLE001 — 실패 목록만 모은다
        return f"{src} -> {dst}: {e}"

start, done, failed = time.time(), 0, []
with ThreadPoolExecutor(max_workers=64) as pool:
    for err in pool.map(copy, pairs):
        done += 1
        if err:
            failed.append(err)
        if done % 5000 == 0:
            print(f"  복사 {done}/{len(pairs)} · {time.time() - start:.0f}초", flush=True)
print(f"  복사 끝 {done}/{len(pairs)} · 실패 {len(failed)} · {time.time() - start:.0f}초", flush=True)
for err in failed[:10]:
    print("  실패 " + err, file=sys.stderr)
sys.exit(1 if failed else 0)
EOF
    report_section "2. DB 갱신" "photos.preview_key → 제 경로"
    echo "  갱신 $(psql_write -c "WITH u AS (UPDATE photos SET preview_key = $NEW_KEY WHERE $MISPLACED RETURNING 1) SELECT count(*) FROM u")행"
  fi
fi

report_section "3. 검증" "키가 전부 제 경로인지 · 갤러리마다 버킷 객체 수가 사진 수와 같은지"
COUNTS=$(psql_write -F ' ' -c "
  SELECT count(*), count(*) FILTER (WHERE preview_key LIKE 'previews/galleries/' || gallery_id || '/%'),
         count(DISTINCT preview_key)
  FROM photos WHERE gallery_id BETWEEN $FROM AND $TO AND deleted_at IS NULL AND preview_key IS NOT NULL")
read -r ALL OWN DISTINCT <<< "$COUNTS"
report_verdict "$([ "$ALL" -gt 0 ] && [ "$ALL" = "$OWN" ] && echo pass || echo fail)" "미리보기 키가 전부 제 갤러리 경로" "$OWN / $ALL"
report_verdict "$([ "$ALL" = "$DISTINCT" ] && echo pass || echo fail)" "서로 다른 미리보기 = 사진 수" "$DISTINCT / $ALL"

MISSING=0
while read -r gid want; do
  # s3api 의 KeyCount 는 CLI 자동 페이지 처리와 겹쳐 None 이 온다 — 목록 줄 수로 센다.
  have=$(aws s3 ls --region "$REGION" "s3://$BUCKET/previews/galleries/$gid/" --recursive | wc -l | tr -d ' ')
  [ "$have" -ge "$want" ] || { echo "  갤러리 $gid: 객체 $have / 사진 $want"; MISSING=$((MISSING + 1)); }
done < <(psql_write -F ' ' -c "
  SELECT gallery_id, count(*) FROM photos
  WHERE gallery_id BETWEEN $FROM AND $TO AND deleted_at IS NULL AND preview_key IS NOT NULL GROUP BY 1 ORDER BY 1")
report_verdict "$([ "$MISSING" = 0 ] && echo pass || echo fail)" "갤러리마다 버킷 객체 ≥ 사진 수" "모자란 갤러리 ${MISSING}개"
report_end
