#!/usr/bin/env bash
# 이미 준비한 회차의 k6 입력을 새 라벨로 다시 쓴다 — 토큰만 새로 서명하고(3시간), 시나리오는 지금 저장소의 users.js 를 올린다.
# 갤러리·사용자를 다시 복제하지 않아 dev DB 쓰기가 없다. 원래 회차의 갤러리가 지워졌으면 k6 가 403·404 를 받는다(스모크로 먼저 확인).
#
#   scripts/load/availability-2-servers/resign.sh dev --label A-0 --from B-1-vc
#
# 읽는 것: dev 버킷 load-results/<from>/inputs/targets.json · SSM <prefix>/jwt.secret
# 쓰는 것: dev 버킷 load-results/<label>/inputs/{targets.json,users.js}
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ "$REPORT_TARGET" = "dev" ] || { echo "dev 전용이다." >&2; exit 1; }
[ -n "$REPORT_LABEL" ] && [ -n "$FROM" ] || { echo "--label 과 --from 이 필요하다." >&2; exit 1; }

BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
OLD="$(mktemp -t a0-old.XXXXXX)"; NEW="$(mktemp -t a0-new.XXXXXX)"
trap 'rm -f "$OLD" "$NEW"' EXIT
aws s3 cp --only-show-errors --region "$REGION" "s3://$BUCKET/load-results/$FROM/inputs/targets.json" "$OLD"

SECRET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/jwt.secret" --with-decryption --query Parameter.Value --output text)
# 서명 모양은 traffic-sqs-redis/prepare.sh 와 같다. providerId 는 옛 토큰의 본문에서 꺼낸다.
JWT_SECRET="$SECRET" python3 - "$OLD" > "$NEW" <<'EOF'
import base64, hashlib, hmac, json, os, sys, time, uuid

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))

secret = os.environ["JWT_SECRET"].encode()
now = int(time.time())
out = []
for t in json.load(open(sys.argv[1])):
    old = json.loads(unb64(t["token"].split(".")[1]))
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64(json.dumps({"sub": old["sub"], "jti": str(uuid.uuid4()), "type": "ACCESS", "role": old.get("role", "USER"),
                              "providerId": old["providerId"], "iat": now, "exp": now + 3 * 3600},
                             separators=(",", ":")).encode())
    sig = b64(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
    out.append({"userId": t["userId"], "galleryId": t["galleryId"], "token": f"{header}.{payload}.{sig}"})
json.dump(out, sys.stdout)
EOF

PREFIX="s3://$BUCKET/load-results/$REPORT_LABEL/inputs"
aws s3 cp --only-show-errors --region "$REGION" "$NEW" "$PREFIX/targets.json"
aws s3 cp --only-show-errors --region "$REGION" scripts/load/traffic-sqs-redis/users.js "$PREFIX/users.js"

read -r USERS GALLERIES < <(python3 -c 'import json,sys; t=json.load(open(sys.argv[1])); print(len(t), len({x["galleryId"] for x in t}))' "$NEW")
echo "준비 끝 $REPORT_LABEL (← $FROM 의 사용자·갤러리, 토큰만 새로)"
echo "  사용자 $USERS 명 · 갤러리 $GALLERIES 개 · 토큰 만료 $(TZ=Asia/Seoul date -v+3H '+%H:%M' 2>/dev/null || TZ=Asia/Seoul date -d '+3 hour' '+%H:%M') KST"
echo "  입력   $PREFIX/{targets.json,users.js}"
