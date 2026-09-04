#!/usr/bin/env bash
# 로컬 Lambda 대역 스크립트(scripts/lambda/*.sh)가 공유하는 접속 정보. scripts/lambda/*.sh · local-ai.sh 가 source 한다.
#
#   . scripts/lib/ai-env.sh
#   ai_env            # DB_*·S3_BUCKET·AWS_REGION 을 export 하고, 로컬 pg 면 docker compose 로 띄운다
#
# DB는 docker-compose.local.yml의 pg(localhost:5432 wes/wes/wes)가 기본이고 DB_* 환경변수로 덮어쓴다.
# S3_BUCKET은 환경변수 → /wes/local/app.storage.bucket(인프라 apply가 기록) 순으로 정한다.
# 운영 RDS를 가리키게 하지 마라 — 미확정 스키마 시험은 부술 수 있는 DB에서만 한다.
ai_env() {
  local wes_root="$1"
  local region="${AWS_REGION:-ap-northeast-2}"
  local bucket_param="/wes/local/app.storage.bucket"

  export DB_HOST="${DB_HOST:-localhost}"
  export DB_PORT="${DB_PORT:-5432}"
  export DB_NAME="${DB_NAME:-wes}"
  export DB_USER="${DB_USER:-wes}"
  export DB_PASSWORD="${DB_PASSWORD:-wes}"
  export DB_SSLMODE="${DB_SSLMODE:-disable}"   # 로컬 pg는 TLS가 없다. RDS 터널이면 require

  if [ -z "${S3_BUCKET:-}" ]; then
    S3_BUCKET=$(aws ssm get-parameter --region "$region" --name "$bucket_param" \
      --query Parameter.Value --output text 2>/dev/null || true)
    if [ -z "$S3_BUCKET" ] || [ "$S3_BUCKET" = "None" ]; then
      echo "S3_BUCKET을 정하지 못했다. 인프라 apply로 $bucket_param 이 생겼는지, AWS 자격증명이 있는지 확인." >&2
      return 1
    fi
  fi
  export S3_BUCKET
  export AWS_REGION="$region"

  if [ "$DB_HOST" = "localhost" ] && [ "$DB_PORT" = "5432" ]; then
    docker compose -f "$wes_root/docker-compose.local.yml" up -d postgres >/dev/null
  fi
}
