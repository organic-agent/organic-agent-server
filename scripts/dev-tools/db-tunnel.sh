#!/usr/bin/env bash
# 로컬 15432 → (SSM) → EC2 → RDS 5432 터널.
# 대상: dev | prod (local 은 터널이 필요 없다)
# 바꾸는 것: 없음(로컬 포트를 열 뿐 — 그 포트로 붙는 명령은 그 환경 DB 에 닿는다)
# RDS는 비공개 DB 서브넷에 있어 로컬에서 직접 붙지 못한다. IntelliJ 등 GUI 클라이언트는
# 이 스크립트를 띄워둔 채 localhost:15432 로 접속한다 (sslmode=require, verify-full 금지).
#
#   scripts/dev-tools/db-tunnel.sh dev          # dev 15433   — 종료는 Ctrl+C
#   scripts/dev-tools/db-tunnel.sh prod [포트]  # 운영 15432 (조회는 읽기 전용 세션으로만)
#   환경 인자는 필수다(기본값 없음 — 예전엔 생략하면 운영이었다). 기본 포트는 prod 15432, dev 15433 —
#   둘을 동시에 띄워 권한을 비교할 수 있게 포트를 나눈다. 환경별 이름(인스턴스 태그·RDS 이름·SSM)은 scripts/lib/env.sh.
#
# 주소는 terraform output이 아니라 AWS API로 조회한다(인프라 repo 상태 없이 어디서든 돌게).
set -euo pipefail

source "$(dirname "$0")/../lib/env.sh"
env_take "${1:-}" && shift || true
env_require
[ "$ENV_TARGET" != "local" ] || { echo "local 은 터널이 필요 없다(로컬 docker pg 5432에 바로 붙는다)." >&2; exit 2; }
PASSWORD_PARAM="$SSM_PREFIX/spring.datasource.password"
LOCAL_PORT="${1:-$TUNNEL_PORT}"

INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=$INSTANCE_TAG" 'Name=instance-state-name,Values=running' \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)

if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
  echo "실행 중인 $INSTANCE_TAG 인스턴스를 찾지 못했다." >&2
  exit 1
fi

RDS_HOST=$(aws rds describe-db-instances --region "$REGION" \
  --db-instance-identifier "$DB_IDENTIFIER" \
  --query 'DBInstances[0].Endpoint.Address' --output text)

if [ -z "$RDS_HOST" ] || [ "$RDS_HOST" = "None" ]; then
  echo "RDS $DB_IDENTIFIER 의 엔드포인트를 찾지 못했다." >&2
  exit 1
fi

if lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "로컬 $LOCAL_PORT 가 이미 사용 중이다. 기존 터널을 끄거나 다른 포트를 넘길 것." >&2
  exit 1
fi

DB_PASSWORD=$(aws ssm get-parameter --region "$REGION" --name "$PASSWORD_PARAM" \
  --with-decryption --query 'Parameter.Value' --output text)

if [ -z "$DB_PASSWORD" ] || [ "$DB_PASSWORD" = "None" ]; then
  echo "$PASSWORD_PARAM 를 읽지 못했다." >&2
  exit 1
fi

echo "인스턴스: $INSTANCE_ID"
echo "RDS:      $RDS_HOST"
echo
echo "  Host      localhost"
echo "  Port      $LOCAL_PORT"
echo "  Database  $DB_NAME"
echo "  User      $DB_USER"
echo "  Password  $DB_PASSWORD"
echo "  sslmode   require        # verify-full 은 터널 때문에 호스트명 검증에 실패한다"
echo

# 비밀번호가 터미널 스크롤백에 남는다. 클립보드에도 넣어두어 위 출력을 지우고(Cmd+K)
# 붙여넣기만 해도 되게 한다.
if command -v pbcopy >/dev/null 2>&1; then
  printf '%s' "$DB_PASSWORD" | pbcopy
  echo "(비밀번호를 클립보드에 복사했다)"
  echo
fi

exec aws ssm start-session --region "$REGION" --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters host="$RDS_HOST",portNumber="5432",localPortNumber="$LOCAL_PORT"
