#!/usr/bin/env bash
# 측정 회차 시작 조건 확인 — 배포된 코드 · GPU 워커 꺼짐 · 다른 분석 잡 없음 · 점수 밀린 사진 없음. 읽기 전용.
# 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 2.3("매 회 시작 조건").
#
#   scripts/load/precheck.sh remote --label R1-1-pre
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}
case "${1:-}" in -h|--help) sed -n 2,5p "$0"; exit 0 ;; esac
GPU_TAG="wes-score-gpu"
report_connect
report_begin "회차 시작 조건" "운영 배포 · GPU 워커 · 분석 잡"
READY="true"

report_section "1. 배포된 코드" "운영 [PROD] Build and Deploy 마지막 실행"
deploy=$(gh run list --workflow "[PROD] Build and Deploy" --limit 1 \
  --json headSha,conclusion,status,createdAt --jq '.[0] | "\(.headSha[0:7])|\(.status)|\(.conclusion)|\(.createdAt)"' 2>/dev/null || echo "")
if [ -z "$deploy" ]; then
  report_verdict info "배포 조회" "gh 로 조회하지 못했다 — 수동 확인"
else
  IFS='|' read -r sha status conclusion created <<< "$deploy"
  report_note "커밋 $sha · $status/$conclusion · $(TZ=Asia/Seoul date -j -f '%Y-%m-%dT%H:%M:%SZ' "$created" '+%m-%d %H:%M KST' 2>/dev/null || echo "$created")"
  if [ "$status" = "completed" ] && [ "$conclusion" = "success" ]; then
    report_verdict pass "운영 배포 완료" "$sha"
  else
    report_verdict fail "운영 배포 완료" "$status/$conclusion"; READY="false"
  fi
fi

report_section "2. GPU 워커" "Name 태그 $GPU_TAG — 회차는 꺼진 상태에서 시작한다(QA-1 환경)"
gpu=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$GPU_TAG" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name]' --output text 2>/dev/null || echo "")
if [ -z "$gpu" ]; then
  report_verdict info "GPU 워커" "인스턴스를 찾지 못했다(Lambda 폴백만 돈다)"
else
  while read -r id type state; do
    report_note "$id · $type · $state"
    if [ "$state" = "stopped" ]; then report_verdict pass "꺼짐" "$state"
    else report_verdict fail "꺼짐" "$state — 유휴 30초 자기 정지를 기다릴 것"; READY="false"; fi
  done <<< "$gpu"
fi

report_section "3. 진행 중인 분석" "ANALYZING · CATEGORIZING 잡과 점수 밀린 사진 — 다른 일이 섞이면 측정이 오염된다"
read -r jobs backlog < <(report_scalar -F ' ' -c "
  SELECT (SELECT count(*) FROM analysis_jobs WHERE status IN ('ANALYZING', 'CATEGORIZING')),
         (SELECT count(*) FROM photos p JOIN galleries g ON g.id = p.gallery_id
            LEFT JOIN photo_analysis a ON a.photo_id = p.id
          WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL AND g.deleted_at IS NULL AND a.analyzed_at IS NULL AND a.error IS NULL)")
if [ "$jobs" != "0" ]; then
  report_sql -c "
  SELECT j.id AS 잡, j.gallery_id AS 갤러리, j.status AS 상태, to_char(j.created_at, 'MM-DD HH24:MI') AS 생성
  FROM analysis_jobs j WHERE j.status IN ('ANALYZING', 'CATEGORIZING') ORDER BY j.id"
fi

if [ "$jobs" = "0" ]; then report_verdict pass "진행 중 잡 없음" "0건"
else report_verdict fail "진행 중 잡 없음" "${jobs}건"; READY="false"; fi
if [ "$backlog" = "0" ]; then report_verdict pass "점수 밀린 사진 없음" "0장"
else report_verdict fail "점수 밀린 사진 없음" "${backlog}장"; READY="false"; fi

report_section "4. 결론"
if [ "$READY" = "true" ]; then report_verdict pass "회차 시작 가능" "업로드를 시작해도 된다"
else report_verdict fail "회차 시작 가능" "위 미달 항목을 먼저 해소"; fi

report_end
