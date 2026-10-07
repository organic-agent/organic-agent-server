#!/usr/bin/env bash
# 로컬 15432 → (SSM) → EC2 → RDS 5432 터널.
# RDS는 비공개 DB 서브넷에 있어 로컬에서 직접 붙지 못한다. IntelliJ 등 GUI 클라이언트는
# 이 스크립트를 띄워둔 채 localhost:15432 로 접속한다 (sslmode=require, verify-full 금지).
#
# 사용법: scripts/db-tunnel.sh [prod|dev] [로컬포트]   — 종료는 Ctrl+C
#   환경을 생략하면 prod(기존 사용법 `db-tunnel.sh 15432` 그대로). 기본 포트는 prod 15432, dev 15433 —
#   둘을 동시에 띄워 권한을 비교할 수 있게 포트를 나눈다.
#
# 주소를 terraform output이 아니라 AWS API로 조회한다. 이 스크립트를 어느 디렉토리에
# 두고 실행하든(다른 저장소로 복사하더라도) 동작하게 하기 위해서다.
set -euo pipefail

TARGET_ENV="prod"
case "${1:-}" in
  prod|dev) TARGET_ENV="$1"; shift ;;
esac

REGION="ap-northeast-2"
DB_USER="wes_admin"
DB_NAME="wes_db"
if [ "$TARGET_ENV" = "dev" ]; then
  INSTANCE_TAG="wes-dev-app"
  DB_IDENTIFIER="wes-dev-db"
  PASSWORD_PARAM="/wes/dev/spring.datasource.password"
  LOCAL_PORT="${1:-15433}"
else
  INSTANCE_TAG="wes-app"
  DB_IDENTIFIER="wes-db"
  PASSWORD_PARAM="/wes/prod/spring.datasource.password"
  LOCAL_PORT="${1:-15432}"
fi

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
