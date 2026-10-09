#!/usr/bin/env bash
# 측정 환경 스냅샷 — 회차 결과를 해석하는 데 필요한 인프라·설정·연결·데이터 규모를 한 화면에 남긴다. 읽기 전용.
# 계획: docs/plans/2026-10-08/upload-5x-10min/02-baseline.md (측정 환경)
#
#   scripts/load/env-snapshot.sh dev --label ENV-dev          # 준비: scripts/db-tunnel.sh dev
#   scripts/load/env-snapshot.sh remote --label ENV-prod      # 운영(읽기만)
#
# 담는 것: 앱 서버 · RDS(클래스·스토리지·파라미터·지표) · Lambda(메모리·동시성·VPC) · GPU 워커 · 커넥션 고정분과 예산 ·
#          주기 작업(@Scheduled) · 데이터 규모(행 수·크기·인덱스)
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}
case "${1:-}" in -h|--help) sed -n 2,9p "$0"; exit 0 ;; esac
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
report_connect
report_begin "측정 환경 스냅샷" "$ENV_NAME 스택 · 리전 $REGION"

cw_avg() {  # namespace metric dimName dimValue minutes → 최근 N분 평균
  aws cloudwatch get-metric-statistics --region "$REGION" --namespace "$1" --metric-name "$2" \
    --dimensions "Name=$3,Value=$4" --start-time "$(python3 -c 'import datetime,sys; print((datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=int(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$5")" \
    --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 300 --statistics Average \
    --query 'Datapoints[-1].Average' --output text 2>/dev/null || echo "?"
}

# ── 1. 앱 서버
report_section "1. 앱 서버 (EC2)" "Name 태그 $RESOURCE_PREFIX-app · 공개 API 한 대"
aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$RESOURCE_PREFIX-app" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,Architecture,Placement.AvailabilityZone,State.Name,LaunchTime]' --output text \
  | while read -r id type arch az state launch; do
      report_note "$id · $type ($arch) · $az · $state · 켜짐 $launch"
      report_note "CPU 크레딧 잔량(최근) $(cw_avg AWS/EC2 CPUCreditBalance InstanceId "$id" 30) · CPU 사용률(최근 평균) $(cw_avg AWS/EC2 CPUUtilization InstanceId "$id" 30)%"
    done
deploy=$(gh run list --workflow "$DEPLOY_WORKFLOW" --limit 1 --json headSha,headBranch,conclusion,createdAt \
  --jq '.[0] | "\(.headSha[0:7]) (\(.headBranch)) \(.conclusion) \(.createdAt)"' 2>/dev/null || echo "?")
report_note "배포된 코드: $deploy"
report_note "커넥션 풀: Hikari 설정 없음 → Spring Boot 기본 maximum-pool-size 10 (src/main/resources 에 hikari 설정 없음)"

# ── 2. RDS
report_section "2. RDS" "인스턴스 $RESOURCE_PREFIX-db"
aws rds describe-db-instances --region "$REGION" --db-instance-identifier "$RESOURCE_PREFIX-db" \
  --query 'DBInstances[0].[DBInstanceClass,Engine,EngineVersion,StorageType,AllocatedStorage,Iops,StorageThroughput,MultiAZ,AvailabilityZone,PerformanceInsightsEnabled,DBParameterGroups[0].DBParameterGroupName]' \
  --output text | while read -r cls eng ver stype alloc iops tput maz az pi pg; do
    report_note "$cls · $eng $ver · 스토리지 $stype ${alloc}GB (IOPS $iops · 처리량 ${tput}MB/s) · Multi-AZ $maz · $az"
    report_note "Performance Insights $pi · 파라미터 그룹 $pg"
  done
report_note "최근 30분 평균: CPU $(cw_avg AWS/RDS CPUUtilization DBInstanceIdentifier "$RESOURCE_PREFIX-db" 30)% · 여유 메모리 $(cw_avg AWS/RDS FreeableMemory DBInstanceIdentifier "$RESOURCE_PREFIX-db" 30) bytes · 연결 $(cw_avg AWS/RDS DatabaseConnections DBInstanceIdentifier "$RESOURCE_PREFIX-db" 30)"
report_sql -c "
  SELECT name AS 파라미터, setting AS 값, unit AS 단위
  FROM pg_settings
  WHERE name IN ('max_connections','superuser_reserved_connections','rds.rds_superuser_reserved_connections',
                 'shared_buffers','effective_cache_size','work_mem','maintenance_work_mem',
                 'random_page_cost','max_parallel_workers_per_gather','idle_in_transaction_session_timeout',
                 'statement_timeout','default_transaction_isolation')
  ORDER BY name"

# ── 3. 커넥션 — 고정분과 예산
report_section "3. 커넥션 — 지금 · 고정분 · 예산" "max_connections 에서 예약분·상시 연결을 빼면 Lambda·워커가 쓸 수 있는 몫이 남는다"
report_sql -c "
  SELECT coalesce(usename, '-') AS 사용자, coalesce(nullif(application_name, ''), '-') AS 애플리케이션,
         coalesce(state, '-') AS 상태, count(*) AS 연결
  FROM pg_stat_activity WHERE backend_type = 'client backend'
  GROUP BY 1, 2, 3 ORDER BY 1, 2, 3"
read -r maxc reserved now_total < <(report_scalar -F ' ' -c "
  SELECT current_setting('max_connections')::int,
         coalesce(current_setting('rds.rds_superuser_reserved_connections', true)::int, 0)
           + current_setting('superuser_reserved_connections')::int,
         (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend')")
report_note "max_connections $maxc · 예약(슈퍼유저) $reserved · 지금 연결 $now_total"
report_note "가드레일 G-1 = max_connections × 80% = $((maxc * 80 / 100))"

# ── 4. Lambda
report_section "4. Lambda" "함수 $RESOURCE_PREFIX-{embedder,score,categorize} · 계정 동시 실행 한도"
for fn in embedder score categorize; do
  name="$RESOURCE_PREFIX-$fn"
  cfg=$(aws lambda get-function-configuration --region "$REGION" --function-name "$name" \
    --query '[MemorySize,Timeout,Architectures[0],EphemeralStorage.Size,length(VpcConfig.SubnetIds||`[]`)]' --output text 2>/dev/null || echo "? ? ? ? ?")
  conc=$(aws lambda get-function-concurrency --region "$REGION" --function-name "$name" --query ReservedConcurrentExecutions --output text 2>/dev/null || echo "?")
  read -r mem to arch eph subnets <<< "$cfg"
  report_note "$name · 메모리 ${mem}MB · 제한 ${to}초 · $arch · 임시저장 ${eph}MB · 예약 동시성 $conc · VPC 서브넷 ${subnets}개"
done
report_note "계정 동시 실행 한도 $(aws lambda get-account-settings --region "$REGION" --query 'AccountLimit.ConcurrentExecutions' --output text)"

# ── 5. GPU 워커
report_section "5. GPU score 워커" "Name 태그 $GPU_TAG_NAME"
aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$GPU_TAG_NAME" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,Placement.AvailabilityZone,State.Name]' --output text \
  | while read -r id type az state; do report_note "$id · $type · $az · $state"; done

# ── 6. 주기 작업
report_section "6. 주기 작업 (@Scheduled, 코드 기준)" "앱 커넥션 풀(10)을 같이 쓴다 — 짧은 주기일수록 상시 풀을 점유"
grep -rhoE '@Scheduled\((fixedDelayString|fixedRateString|cron) = "[^"]*"' "$REPO_ROOT/src/main/kotlin" 2>/dev/null \
  | sed -E 's/@Scheduled\(//' | sort | uniq -c | sed 's/^/    /'
grep -rlE '@Scheduled' "$REPO_ROOT/src/main/kotlin" | sed "s|$REPO_ROOT/src/main/kotlin/com/soma/wes/|    · |"

# ── 7. 데이터 규모
report_section "7. 데이터 규모" "행 수(추정)·테이블+인덱스 크기·인덱스 수 — 갤러리가 쌓일수록 조회 비용이 오른다"
report_sql -c "
  SELECT c.relname AS 테이블, c.reltuples::bigint AS 행_추정,
         pg_size_pretty(pg_total_relation_size(c.oid)) AS 전체크기,
         pg_size_pretty(pg_relation_size(c.oid)) AS 테이블만,
         (SELECT count(*) FROM pg_index i WHERE i.indrelid = c.oid) AS 인덱스
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relkind = 'r'
  ORDER BY pg_total_relation_size(c.oid) DESC LIMIT 12"
report_sql -c "
  SELECT count(*) AS 갤러리, (SELECT count(*) FROM photos) AS 사진_전체,
         (SELECT count(*) FROM photos WHERE deleted_at IS NULL) AS 사진_살아있음,
         (SELECT count(*) FROM photo_analysis) AS 분석행
  FROM galleries"

report_end
