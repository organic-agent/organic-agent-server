#!/usr/bin/env bash
# 측정 회차 시작 조건 확인 — 배포된 코드 · AI 이미지 최신 · GPU 워커 꺼짐 · 분석 설정 · 다른 분석 잡 없음 · 점수 밀린 사진 없음. 읽기 전용.
# 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 2.0(dev 환경)·2.3("매 회 시작 조건").
#
#   scripts/load/precheck.sh dev --label R2-pre          # dev 스택 (터널: scripts/db-tunnel.sh dev)
#   scripts/load/precheck.sh remote --label R1-1-pre     # 운영
#
#   --gpu off   GPU 를 끈 회차(R6·R7) — app.analysis.gpu.enabled=false 를 통과로 본다. 기본은 켜진 설정(true)을 기대한다
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

EXPECT_GPU="true"
while [ $# -gt 0 ]; do
  case "$1" in
    --gpu) [ "$2" = off ] && EXPECT_GPU="false"; shift ;;
    -h|--help) sed -n 2,8p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
AI_REPO="organic-agent/organic-agent-ai"
report_connect
report_begin "회차 시작 조건" "$ENV_NAME 배포 · AI 이미지 · GPU 워커 · 분석 설정 · 분석 잡"
READY="true"

report_section "1. 배포된 코드" "$DEPLOY_WORKFLOW 마지막 실행"
deploy=$(gh run list --workflow "$DEPLOY_WORKFLOW" --limit 1 \
  --json headSha,headBranch,conclusion,status,createdAt \
  --jq '.[0] | "\(.headSha[0:7])|\(.headBranch)|\(.status)|\(.conclusion)|\(.createdAt)"' 2>/dev/null || echo "")
if [ -z "$deploy" ]; then
  report_verdict info "배포 조회" "gh 로 조회하지 못했다 — 수동 확인"
else
  IFS='|' read -r sha branch status conclusion created <<< "$deploy"
  report_note "커밋 $sha ($branch) · $status/$conclusion · $(TZ=Asia/Seoul date -j -f '%Y-%m-%dT%H:%M:%SZ' "$created" '+%m-%d %H:%M KST' 2>/dev/null || echo "$created")"
  if [ "$status" = "completed" ] && [ "$conclusion" = "success" ]; then
    report_verdict pass "$ENV_NAME 배포 완료" "$sha"
  else
    report_verdict fail "$ENV_NAME 배포 완료" "$status/$conclusion"; READY="false"
  fi
fi

# AI repo CD 는 main → 운영 ECR 만 민다. dev 는 손으로 민다 — 모듈의 마지막 main 커밋보다 이미지가 오래됐으면 옛 코드로 잰다.
report_section "2. AI 이미지" "함수가 쓰는 이미지(·GPU :gpu 태그)의 푸시 시각 ≥ AI main 에서 그 모듈의 마지막 커밋 시각"
iso_epoch() { date -j -u -f '%Y-%m-%dT%H:%M:%S' "${1:0:19}" +%s 2>/dev/null || echo 0; }
image_check() {  # 모듈 이름 · ECR 저장소 · 이미지 다이제스트 또는 태그(imageDigest=… | imageTag=…) · 표시 이름
  local module="$1" repo="$2" image_id="$3" label="${4:-$2}" commit pushed
  commit=$(gh api "repos/$AI_REPO/commits?sha=main&path=$module&per_page=1" \
    --jq '.[0] | "\(.sha[0:7])|\(.commit.committer.date)"' 2>/dev/null || echo "")
  pushed=$(aws ecr describe-images --region "$REGION" --repository-name "$repo" --image-ids "$image_id" \
    --query 'imageDetails[0].imagePushedAt' --output text 2>/dev/null || echo "")
  if [ -z "$commit" ] || [ -z "$pushed" ] || [ "$pushed" = "None" ]; then
    report_verdict info "$label" "조회하지 못했다 — 수동 확인"; return
  fi
  local c_sha="${commit%%|*}" c_date="${commit#*|}"
  # 푸시 시각은 +09:00 로 온다 — UTC 로 맞춘다.
  local p_utc; p_utc=$(TZ=UTC date -j -f '%Y-%m-%dT%H:%M:%S%z' "$(sed -E 's/\.[0-9]+//; s/([+-][0-9]{2}):([0-9]{2})$/\1\2/' <<<"$pushed")" '+%Y-%m-%dT%H:%M:%S')
  report_note "$label · 푸시 $(TZ=Asia/Seoul date -r "$(iso_epoch "$p_utc")" '+%m-%d %H:%M KST') · AI main $module 마지막 커밋 $c_sha $(TZ=Asia/Seoul date -r "$(iso_epoch "$c_date")" '+%m-%d %H:%M KST')"
  if [ "$(iso_epoch "$p_utc")" -ge "$(iso_epoch "$c_date")" ]; then
    report_verdict pass "$label 최신" "$c_sha 이후 이미지"
  else
    report_verdict fail "$label 최신" "이미지가 $c_sha 보다 오래됨 — $module 다시 배포"; READY="false"
  fi
}
for module in embedder score categorize; do
  fn="$RESOURCE_PREFIX-$module"
  uri=$(aws lambda get-function --region "$REGION" --function-name "$fn" --query 'Code.ResolvedImageUri' --output text 2>/dev/null || echo "")
  if [ -z "$uri" ] || [ "$uri" = "None" ]; then report_verdict info "$fn" "함수를 찾지 못했다"; continue; fi
  image_check "$module" "$fn" "imageDigest=${uri##*@}"
done
image_check score "$RESOURCE_PREFIX-score" "imageTag=gpu" "$RESOURCE_PREFIX-score:gpu (GPU 워커)"

report_section "3. GPU 워커" "Name 태그 $GPU_TAG_NAME — 회차는 꺼진 상태에서 시작한다(QA-1 환경). 정지는 ≈ 6분 걸린다"
gpu=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$GPU_TAG_NAME" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name]' --output text 2>/dev/null || echo "")
if [ -z "$gpu" ]; then
  report_verdict info "GPU 워커" "인스턴스를 찾지 못했다(Lambda 폴백만 돈다)"
else
  while read -r id type state; do
    report_note "$id · $type · $state"
    if [ "$state" = "stopped" ]; then report_verdict pass "꺼짐" "$state"
    else report_verdict fail "꺼짐" "$state — 유휴 30초 자기 정지·정지 처리(≈ 6분)를 기다릴 것"; READY="false"; fi
  done <<< "$gpu"
fi

# R6·R7 은 dev 에서 gpu.enabled=false · fallback-interval 을 바꿔 잰다. 그 뒤 회차가 바뀐 설정으로 돌지 않게 여기서 본다.
report_section "4. 분석 설정" "Parameter Store $SSM_PREFIX/app.analysis.gpu.* — 기대: enabled=$EXPECT_GPU"
param() {
  aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/$1" --query 'Parameter.Value' --output text 2>/dev/null || echo "(없음)"
}
gpu_enabled=$(param app.analysis.gpu.enabled)
fallback_after=$(param app.analysis.gpu.fallback-after)
fallback_interval=$(param app.analysis.gpu.fallback-interval)
report_note "gpu.enabled=$gpu_enabled · fallback-after=$fallback_after · fallback-interval=$fallback_interval  ((없음) = 앱 기본값 PT10M)"
if [ "$gpu_enabled" = "$EXPECT_GPU" ]; then report_verdict pass "gpu.enabled" "$gpu_enabled"
else report_verdict fail "gpu.enabled" "$gpu_enabled (기대 $EXPECT_GPU) — 바꾼 뒤엔 앱 재시작이 필요하다"; READY="false"; fi
if [ "$EXPECT_GPU" = "true" ] && [ "$fallback_interval" != "(없음)" ]; then
  report_verdict info "fallback-interval" "$fallback_interval — R6·R7 뒤 되돌리지 않았는지 확인"
fi

report_section "5. 진행 중인 분석" "ANALYZING · CATEGORIZING 잡과 점수 밀린 사진 — 다른 일이 섞이면 측정이 오염된다"
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

report_section "6. 결론"
if [ "$READY" = "true" ]; then report_verdict pass "회차 시작 가능" "업로드(재생)를 시작해도 된다"
else report_verdict fail "회차 시작 가능" "위 미달 항목을 먼저 해소"; fi

report_end
