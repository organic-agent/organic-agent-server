#!/usr/bin/env bash
# 가용성 회차 판정 — k6 요청 CSV(run-k6.sh --csv)와 자극 기록(stimulus.sh)으로 초 단위 실패 타임라인을 그리고 판정한다(#313 02).
#
#   scripts/load/availability-2-servers/gap.sh dev --label A-0
#   scripts/load/availability-2-servers/gap.sh dev --label A-0 --csv 경로 --stimulus 경로      # 로컬 파일로(도구 확인용)
#
# 입력이 로컬(docs/experiments/load-runs/<label>-{requests.csv.gz,stimulus.env})에 없으면 dev 버킷 load-results/<label>/ 에서 받는다.
# 판정: 배포(deploy) = QA-1 자극 구간 실패율 < 0.1% · 5초 넘는 공백 0 / 장애(kill) = 회복 T0→T1 ≤ 30초 · T1 뒤 실패 0(1대의 기준값).
# DB·dev 쓰기 없음.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common "$@"; set -- "${REPORT_REST[@]+"${REPORT_REST[@]}"}"

RUNS_DIR="${LOAD_RUNS_DIR:-docs/experiments/load-runs}"
CSV_PATH=""; STIM_PATH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --csv) CSV_PATH="$2"; shift ;;
    --stimulus) STIM_PATH="$2"; shift ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다." >&2; exit 1; }
mkdir -p "$RUNS_DIR"
CSV_PATH="${CSV_PATH:-$RUNS_DIR/$REPORT_LABEL-requests.csv.gz}"
STIM_PATH="${STIM_PATH:-$RUNS_DIR/$REPORT_LABEL-stimulus.env}"
if [ ! -f "$CSV_PATH" ] || [ ! -f "$STIM_PATH" ]; then
  BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
  [ -f "$CSV_PATH" ] || aws s3 cp --only-show-errors --region "$REGION" "s3://$BUCKET/load-results/$REPORT_LABEL/requests.csv.gz" "$CSV_PATH"
  [ -f "$STIM_PATH" ] || aws s3 cp --only-show-errors --region "$REGION" "s3://$BUCKET/load-results/$REPORT_LABEL/stimulus.env" "$STIM_PATH"
fi

TABLES=$(mktemp -d -t a0-gap.XXXXXX); trap 'rm -rf "$TABLES"' EXIT
python3 "$(dirname "$0")/gap.py" "$CSV_PATH" "$STIM_PATH" "$TABLES"
# shellcheck disable=SC1091
source "$TABLES/verdict.env"

DB_NAME="DB 안 씀"
report_begin "배포·장애 구간의 초 단위 실패 타임라인" "k6 요청 CSV $(basename "$CSV_PATH") · 자극 $KIND"

report_section "1. 요약" "실패 = 응답 없음 또는 5xx · F = 첫 실패 · T1 = 그 뒤 성공만 10초 이어진 시작 · 자극 구간 = [T0−10초, T1+30초]"
cat "$TABLES/summary.txt"
report_section "2. 공백 (성공 응답이 하나도 없는 연속 초, 긴 순 10개)"
cat "$TABLES/gaps.txt"
report_section "3. 초별 (첫 실패 5초 전 ~ 회복 5초 뒤)" "코드 0 = 응답 없음(연결 오류·타임아웃)"
cat "$TABLES/seconds.txt"

report_section "4. 판정 (01 시나리오)"
if [ "$KIND" = "deploy" ]; then
  awk "BEGIN{exit !($WINDOW_RATE < 0.1)}" && v=pass || v=fail; report_verdict "$v" "QA-1 자극 구간 실패율 < 0.1%" "${WINDOW_RATE}%"
  [ "$GAPS_OVER5" -eq 0 ] && v=pass || v=fail; report_verdict "$v" "QA-1 5초 넘는 공백 0" "${GAPS_OVER5}개 (가장 긴 것 ${LONGEST_GAP}초)"
  report_verdict info "회복 T0 → T1" "${RECOVER:-회복 못 함}${RECOVER:+초}"
else
  if [ -n "$RECOVER" ]; then
    [ "$RECOVER" -le 30 ] && v=pass || v=fail; report_verdict "$v" "회복 T0 → T1 ≤ 30초 (QA-2 기준을 1대에 대 봄)" "${RECOVER}초"
  else
    report_verdict fail "회복 T0 → T1 ≤ 30초" "회복 못 함"
  fi
  [ "${AFTER_T1:-1}" = "0" ] && v=pass || v=fail; report_verdict "$v" "T1 뒤 실패 0" "${AFTER_T1:-—}"
  report_verdict info "자극 구간 실패율 · 가장 긴 공백" "${WINDOW_RATE}% · ${LONGEST_GAP}초"
fi
report_note "원본: $CSV_PATH · $STIM_PATH"
report_end
