#!/usr/bin/env bash
# 부하 측정 비용 — 대상 갤러리의 측정 창(T0 → 마지막 DONE + 꼬리) 동안 쓴 Lambda·GPU·Bedrock·S3 요청을 지표로 세어 단가를 곱한다. 읽기 전용.
# 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 2.4(비용 어림 → 6장 실제 값).
#
#   scripts/load/cost.sh dev --label R2-cost --ids 101,102 [--tail 5min]       # dev 스택(wes-dev-* 함수·GPU)
#   scripts/load/cost.sh remote --label R1-1-cost --ids 101,102 [--tail 5min]    # 운영
#
#   --ids    대상 갤러리 id (쉼표). 창은 min(T0) → max(DONE) + tail. DONE 없는 갤러리가 있으면 지금까지.
#   --tail   DONE 뒤에 더 볼 시간(GPU 자기 정지·마지막 Lambda 꼬리). 기본 5min
#
# 주의: CloudWatch 지표는 함수·모델·인스턴스 단위라 같은 창 안의 다른 갤러리 처리도 섞인다(측정 중 실사용이 없어야 정확).
#       단가는 공시가 기본값이다(pricing API는 SCP로 막힘) — 환경 변수로 덮어쓴다: GPU_HOURLY, LAMBDA_GBS, LAMBDA_REQ,
#       SONNET_IN, SONNET_OUT (100만 토큰당), S3_PUT, S3_GET (1,000건당).
#       Bedrock 지표는 모델 단위라 dev 측정에도 같은 창의 운영 categorize 호출이 섞인다.
set -euo pipefail
source "$(dirname "$0")/lib/report.sh"
report_parse_common "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}

IDS=""; TAIL="5min"
while [ $# -gt 0 ]; do
  case "$1" in
    --ids) IDS="$2"; shift ;;
    --tail) TAIL="$2"; shift ;;
    -h|--help) sed -n 2,15p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$IDS" ] || { echo "--ids 가 필요하다 (--help)" >&2; exit 1; }
[ "$REPORT_TARGET" != local ] || { echo "비용은 AWS 지표라 remote·dev 만 의미가 있다." >&2; exit 1; }
report_connect

# 단가(USD) — 서울 리전 공시가. Bedrock 은 categorize 가 쓰는 us-east-1 교차 리전 프로필.
GPU_HOURLY="${GPU_HOURLY:-1.0060}"          # g6.xlarge 온디맨드 시간당 (확인 후 덮어쓸 것)
LAMBDA_GBS="${LAMBDA_GBS:-0.0000166667}"   # x86 GB-초
LAMBDA_REQ="${LAMBDA_REQ:-0.20}"           # 100만 요청
SONNET_IN="${SONNET_IN:-3.00}"; SONNET_OUT="${SONNET_OUT:-15.00}"
S3_PUT="${S3_PUT:-0.0045}"; S3_GET="${S3_GET:-0.00035}"

LAMBDA_FNS="${LAMBDA_FNS:-$RESOURCE_PREFIX-embedder $RESOURCE_PREFIX-score $RESOURCE_PREFIX-categorize}"
GPU_TAG="${GPU_TAG:-$GPU_TAG_NAME}"
BEDROCK_REGION="us-east-1"; BEDROCK_MODEL="${BEDROCK_MODEL:-us.anthropic.claude-sonnet-4-6}"

# 측정 창 — DB 시각을 UTC ISO 로.
IFS='|' read -r START END PHOTOS ALL_DONE < <(report_scalar -c "
  WITH p AS (SELECT gallery_id, min(created_at) t0, count(*) n FROM photos
             WHERE gallery_id = ANY('{$IDS}'::bigint[]) AND deleted_at IS NULL GROUP BY gallery_id),
       d AS (SELECT DISTINCT ON (j.gallery_id) j.gallery_id,
                    (SELECT min(e.created_at) FROM analysis_job_events e WHERE e.job_id = j.id AND e.type = 'DONE') t_done
             FROM analysis_jobs j WHERE j.gallery_id = ANY('{$IDS}'::bigint[]) ORDER BY j.gallery_id, j.id DESC)
  SELECT to_char(min(p.t0) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),
         to_char(least(now(), CASE WHEN bool_and(d.t_done IS NOT NULL) THEN max(d.t_done) + interval '$TAIL' ELSE now() END)
                 AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),
         sum(p.n), bool_and(d.t_done IS NOT NULL)
  FROM p LEFT JOIN d USING (gallery_id)")
[ -n "$START" ] || { echo "대상 갤러리에 사진이 없다." >&2; exit 1; }

kst() { TZ=Asia/Seoul date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$1" '+%m-%d %H:%M:%S' 2>/dev/null || echo "$1"; }
epoch() { date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s; }
WIN_SEC=$(( $(epoch "$END") - $(epoch "$START") ))
calc() { awk "BEGIN { printf \"%.4f\", $1 }"; }

# CloudWatch 합계 하나 — 창을 1분 단위로 받아 더한다(최대 1440점 = 24시간).
cw_sum() {  # region namespace metric dimName dimValue stat
  local period=60
  [ "$WIN_SEC" -gt 86000 ] && period=300
  aws cloudwatch get-metric-statistics --region "$1" --namespace "$2" --metric-name "$3" \
    --dimensions "Name=$4,Value=$5" --start-time "$START" --end-time "$END" --period "$period" \
    --statistics "$6" --output json \
    | jq -r --arg s "$6" 'if $s == "Maximum" then ([.Datapoints[].Maximum] | max // 0) else ([.Datapoints[].Sum] | add // 0) end'
}

report_begin "업로드 → AI 폴더 비용 (측정 창 동안의 지표 × 단가)" "갤러리 $IDS · 사진 ${PHOTOS}장"
report_note "창: $(kst "$START") → $(kst "$END") KST ($((WIN_SEC / 60))분 $((WIN_SEC % 60))초)$([ "$ALL_DONE" = t ] || echo ' · DONE 아님 — 지금까지')"

# ── Lambda
report_section "1. Lambda" "Duration 합 × 메모리 = GB-초, 요청 수, 최대 동시 실행, 스로틀"
printf "    %-20s %8s %10s %6s %10s %8s %10s\n" 함수 요청 "실행(초)" "메모리" "GB-초" 동시최대 "비용(\$)"
LAMBDA_TOTAL=0
for fn in $LAMBDA_FNS; do
  mem=$(aws lambda get-function-configuration --region "$REGION" --function-name "$fn" --query MemorySize --output text)
  inv=$(cw_sum "$REGION" AWS/Lambda Invocations FunctionName "$fn" Sum)
  dur=$(cw_sum "$REGION" AWS/Lambda Duration FunctionName "$fn" Sum)
  conc=$(cw_sum "$REGION" AWS/Lambda ConcurrentExecutions FunctionName "$fn" Maximum)
  thr=$(cw_sum "$REGION" AWS/Lambda Throttles FunctionName "$fn" Sum)
  gbs=$(calc "$dur / 1000 * $mem / 1024")
  cost=$(calc "$gbs * $LAMBDA_GBS + $inv / 1000000 * $LAMBDA_REQ")
  LAMBDA_TOTAL=$(calc "$LAMBDA_TOTAL + $cost")
  printf "    %-20s %8.0f %10.1f %5sM %10.1f %8.0f %10.4f\n" "$fn" "$inv" "$(calc "$dur / 1000")" "$mem" "$gbs" "$conc" "$cost"
  awk "BEGIN { exit !($thr > 0) }" && report_verdict info "$fn 스로틀" "$(printf '%.0f' "$thr")건"
done

# ── GPU
report_section "2. GPU score 워커 (EC2, Name=$GPU_TAG)" "켜진 시간 = 창과 겹친 [LaunchTime → 정지 시각/지금] · 초 단위 과금(최소 60초)"
report_note "LaunchTime 은 마지막 켜짐 하나뿐이다 — 창 안에서 두 번 이상 켜졌다면 CPU 지표 점 수(5분 단위)를 함께 본다."
printf "    %-21s %-10s %-8s %-15s %-15s %8s %7s %10s\n" 인스턴스 타입 상태 켜짐 꺼짐 "겹친(초)" "CW점" "비용(\$)"
GPU_TOTAL=0
while read -r iid itype state launch reason; do
  [ -n "$iid" ] || continue
  stop_iso="$END"
  if [ "$state" = stopped ]; then
    t=$(sed -nE 's/.*\(([0-9-]+ [0-9:]+) GMT\).*/\1/p' <<<"$reason")
    [ -n "$t" ] && stop_iso="${t/ /T}Z"
  fi
  launch_iso="${launch%%+*}Z"
  s=$(( $(epoch "$launch_iso") > $(epoch "$START") ? $(epoch "$launch_iso") : $(epoch "$START") ))
  e=$(( $(epoch "$stop_iso") < $(epoch "$END") ? $(epoch "$stop_iso") : $(epoch "$END") ))
  over=$(( e > s ? e - s : 0 )); [ "$over" -gt 0 ] && [ "$over" -lt 60 ] && over=60
  pts=$(aws cloudwatch get-metric-statistics --region "$REGION" --namespace AWS/EC2 --metric-name CPUUtilization \
          --dimensions "Name=InstanceId,Value=$iid" --start-time "$START" --end-time "$END" --period 300 \
          --statistics Average --query 'length(Datapoints)' --output text)
  cost=$(calc "$over / 3600 * $GPU_HOURLY")
  GPU_TOTAL=$(calc "$GPU_TOTAL + $cost")
  printf "    %-21s %-10s %-8s %-15s %-15s %8d %7s %10.4f\n" "$iid" "$itype" "$state" "$(kst "$launch_iso")" \
    "$([ "$state" = stopped ] && kst "$stop_iso" || echo '(켜져 있음)')" "$over" "$pts" "$cost"
done < <(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$GPU_TAG" \
           --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name,LaunchTime,StateTransitionReason]' \
           --output text | tr '\t' ' ')

# ── Bedrock
report_section "3. Bedrock (categorize 그룹 이름·배정)" "$BEDROCK_MODEL @ $BEDROCK_REGION · 입력 \$$SONNET_IN / 출력 \$$SONNET_OUT (100만 토큰당)"
b_inv=$(cw_sum "$BEDROCK_REGION" AWS/Bedrock Invocations ModelId "$BEDROCK_MODEL" Sum)
b_in=$(cw_sum "$BEDROCK_REGION" AWS/Bedrock InputTokenCount ModelId "$BEDROCK_MODEL" Sum)
b_out=$(cw_sum "$BEDROCK_REGION" AWS/Bedrock OutputTokenCount ModelId "$BEDROCK_MODEL" Sum)
BEDROCK_TOTAL=$(calc "$b_in / 1000000 * $SONNET_IN + $b_out / 1000000 * $SONNET_OUT")
printf "    %-10s %8.0f    %-10s %10.0f    %-10s %10.0f    %-8s %10.4f\n" 호출 "$b_inv" "입력 토큰" "$b_in" "출력 토큰" "$b_out" "비용(\$)" "$BEDROCK_TOTAL"

# ── S3 요청 (어림)
report_section "4. S3 요청 (어림 — 버킷 요청 지표가 꺼져 있어 사진 수로 계산)" "PUT 2건/장(원본·미리보기) · GET 2건/장(임베더 원본·점수 미리보기)"
S3_TOTAL=$(calc "$PHOTOS * 2 / 1000 * $S3_PUT + $PHOTOS * 2 / 1000 * $S3_GET")
printf "    %-10s %8d    %-8s %10.4f\n" 사진 "$PHOTOS" "비용(\$)" "$S3_TOTAL"

# ── 합계
TOTAL=$(calc "$LAMBDA_TOTAL + $GPU_TOTAL + $BEDROCK_TOTAL + $S3_TOTAL")
report_section "5. 합계" "기준: 단건 7,200장 ≈ \$0.45 (embedder \$0.18 · GPU \$0.12 · Bedrock \$0.08 · S3 \$0.07, 2026-09-10)"
report_verdict info "Lambda" "\$$LAMBDA_TOTAL"
report_verdict info "GPU (단가 \$$GPU_HOURLY/h)" "\$$GPU_TOTAL"
report_verdict info "Bedrock" "\$$BEDROCK_TOTAL"
report_verdict info "S3 요청(어림)" "\$$S3_TOTAL"
report_verdict info "합계" "\$$TOTAL"
report_verdict info "7,200장 환산" "\$$(calc "$TOTAL / $PHOTOS * 7200")  (1,000장당 \$$(calc "$TOTAL / $PHOTOS * 1000"))"

report_end
