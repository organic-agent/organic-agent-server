#!/usr/bin/env bash
# R-3 사용자 트래픽 준비 — dev 쓰기. 분석이 끝난 갤러리를 복제하고, 부부 역할 테스트 사용자를 만들어 갤러리 멤버로 넣고,
# k6 입력(targets.json: 사용자·갤러리·토큰)과 시나리오(users.js)를 dev 버킷 load-results/<label>/inputs/ 에 올린다.
#
#   scripts/load/traffic-sqs-redis/prepare.sh dev --label R-3-a [--source 20] [--galleries 10] [--users 400]
#
# 쓰는 것: 갤러리 N개(사진·분석 행 복제, S3 객체는 원본을 가리킴 — 업로드 없음) · users(provider GOOGLE, provider_id load-user-NNNN)
#          · gallery_members. 사용자는 회차끼리 재사용한다(이미 있으면 그대로). 갤러리는 회차마다 새로 — 지우기는 cleanup.sh.
# 토큰은 /wes/dev/jwt.secret 로 직접 서명한다(ACCESS, 3시간). 버킷에만 올리고 로컬에는 임시 파일로만 둔다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

SOURCE=20; GALLERIES=10; USERS=400
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift ;;
    --galleries) GALLERIES="$2"; shift ;;
    --users) USERS="$2"; shift ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다(예: R-3-a)." >&2; exit 1; }
[[ "$REPORT_LABEL" =~ ^[A-Za-z0-9-]+$ ]] || { echo "--label 은 영문·숫자·하이픈만(제목 LIKE 패턴에 들어간다): $REPORT_LABEL" >&2; exit 1; }
report_connect

psql_write() {
  PGPASSWORD="$DB_PASSWORD" docker run --rm -i -e PGPASSWORD -e PGCLIENTENCODING=UTF8 pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}

BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
TITLE_PREFIX="[부하 $REPORT_LABEL] 사용자"

# 1. 갤러리 복제 — 복제 스크립트가 만든 갤러리를 id 범위로 찾아 회차 제목을 붙인다(정리 때 제목으로 고른다).
BEFORE=$(psql_write -c "SELECT coalesce(max(id), 0) FROM galleries")
scripts/load/clone-gallery-photos.sh dev --source "$SOURCE" --count "$GALLERIES" --yes >/dev/null
GALLERY_IDS=$(psql_write -c "
  WITH made AS (SELECT id, row_number() OVER (ORDER BY id) AS k FROM galleries WHERE id > $BEFORE)
  UPDATE galleries g SET title = '$TITLE_PREFIX ' || made.k || '/$GALLERIES', status = 'OPEN',
         selection_deadline = NULL, max_selectable_photo_count = NULL, updated_at = now()
  FROM made WHERE g.id = made.id RETURNING g.id" | sort -n | paste -sd, -)
COUNT=$(echo "$GALLERY_IDS" | tr ',' '\n' | grep -c .)
[ "$COUNT" -eq "$GALLERIES" ] || { echo "복제 갤러리가 $COUNT 개다(기대 $GALLERIES): $GALLERY_IDS" >&2; exit 1; }

# 2. 테스트 사용자와 멤버 — 사용자 i 는 갤러리 (i mod N) 의 부부다.
TARGETS_SQL="
  WITH wanted AS (SELECT n, 'load-user-' || lpad(n::text, 4, '0') AS pid FROM generate_series(1, $USERS) n),
  ins AS (
    INSERT INTO users (provider, provider_id, nickname, role, created_at, updated_at)
    SELECT 'GOOGLE', pid, '부하 사용자 ' || n, 'USER', now(), now() FROM wanted
    ON CONFLICT (provider, provider_id) DO NOTHING RETURNING id),
  galleries_k AS (SELECT id, row_number() OVER (ORDER BY id) - 1 AS k FROM unnest('{$GALLERY_IDS}'::bigint[]) id),
  pairs AS (
    SELECT u.id AS user_id, u.provider_id, gk.id AS gallery_id
    FROM wanted w JOIN users u ON u.provider = 'GOOGLE' AND u.provider_id = w.pid
    JOIN galleries_k gk ON gk.k = (w.n - 1) % $GALLERIES),
  members AS (
    INSERT INTO gallery_members (gallery_id, user_id, created_at, updated_at, version)
    SELECT gallery_id, user_id, now(), now(), 0 FROM pairs
    ON CONFLICT (gallery_id, user_id) DO NOTHING RETURNING id)
  SELECT user_id || ' ' || provider_id || ' ' || gallery_id FROM pairs ORDER BY user_id"
PAIRS_FILE=$(mktemp -t r3-pairs.XXXXXX)
TARGETS_FILE=$(mktemp -t r3-targets.XXXXXX)
trap 'rm -f "$PAIRS_FILE" "$TARGETS_FILE"' EXIT
psql_write -c "$TARGETS_SQL" > "$PAIRS_FILE"
# 같은 문장 안의 INSERT 결과는 같은 스냅숏에서 안 보인다 — 첫 실행이면 한 번 더 읽어 짝을 채운다.
[ "$(wc -l < "$PAIRS_FILE")" -eq "$USERS" ] || psql_write -c "$TARGETS_SQL" > "$PAIRS_FILE"
[ "$(wc -l < "$PAIRS_FILE")" -eq "$USERS" ] || { echo "사용자·멤버 짝이 $(wc -l < "$PAIRS_FILE") 개다(기대 $USERS)." >&2; exit 1; }

# 3. 토큰 서명 → targets.json
SECRET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/jwt.secret" --with-decryption --query Parameter.Value --output text)
JWT_SECRET="$SECRET" python3 - "$PAIRS_FILE" > "$TARGETS_FILE" <<'EOF'
import base64, hashlib, hmac, json, os, sys, time, uuid

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

secret = os.environ["JWT_SECRET"].encode()
now = int(time.time())
targets = []
for line in open(sys.argv[1]):
    user_id, provider_id, gallery_id = line.split()
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64(json.dumps({"sub": user_id, "jti": str(uuid.uuid4()), "type": "ACCESS", "role": "USER",
                              "providerId": provider_id, "iat": now, "exp": now + 3 * 3600},
                             separators=(",", ":")).encode())
    sig = b64(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
    targets.append({"userId": int(user_id), "galleryId": int(gallery_id), "token": f"{header}.{payload}.{sig}"})
json.dump(targets, sys.stdout)
EOF

# 4. 발생기가 읽을 입력
PREFIX="s3://$BUCKET/load-results/$REPORT_LABEL/inputs"
aws s3 cp --only-show-errors "$TARGETS_FILE" "$PREFIX/targets.json"
aws s3 cp --only-show-errors scripts/load/traffic-sqs-redis/users.js "$PREFIX/users.js"

echo "준비 끝 $REPORT_LABEL"
echo "  갤러리 $GALLERIES 개: $GALLERY_IDS  (원본 $SOURCE, 제목 '$TITLE_PREFIX k/$GALLERIES')"
echo "  사용자 $USERS 명 → 갤러리마다 $((USERS / GALLERIES)) 명"
echo "  입력   $PREFIX/{targets.json,users.js}"
echo "다음: scripts/load/traffic-sqs-redis/run-k6.sh dev --label $REPORT_LABEL --profile full"
