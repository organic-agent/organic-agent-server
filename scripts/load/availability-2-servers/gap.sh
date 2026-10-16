#!/usr/bin/env bash
# 가용성 회차 판정 — k6 요청 CSV(run-k6.sh --csv)와 자극 기록(stimulus.sh)으로 초 단위 실패 타임라인을 그리고 판정한다(#313 02).
#
#   scripts/load/availability-2-servers/gap.sh dev --label A-0
#   scripts/load/availability-2-servers/gap.sh dev --label A-0 --csv 경로 --stimulus 경로      # 로컬 파일로(도구 확인용)
#
# 입력이 로컬(docs/experiments/load-runs/<label>-{requests.csv.gz,stimulus.env,events.tsv,targets.tsv,servers.txt})에 없으면
# dev 버킷 load-results/<label>/ 에서 받는다(사건·대상·서버 기록은 서버 여러 대 도구 뒤 회차에만 있다 — 없으면 그 칸을 뺀다).
# 판정: 배포(deploy) = QA-1 자극 구간 실패율 < 0.1% · 5초 넘는 공백 0 / 장애(kill) = QA-2 회복 T0→T1 ≤ 30초 · T1 뒤 실패 0
#       관찰(watch, A-2m) = ADR-0001 §6 대상 이탈 0 · GC 최대 멈춤 < 3초 · 실패율 < 1%(p95 는 run-k6.sh 판정).
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
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -n "$REPORT_LABEL" ] || { echo "--label 이 필요하다." >&2; exit 1; }
mkdir -p "$RUNS_DIR"
CSV_PATH="${CSV_PATH:-$RUNS_DIR/$REPORT_LABEL-requests.csv.gz}"
STIM_PATH="${STIM_PATH:-$RUNS_DIR/$REPORT_LABEL-stimulus.env}"
EXTRA="$(dirname "$STIM_PATH")/$REPORT_LABEL"
BUCKET=""
fetch() {  # 로컬에 없으면 dev 버킷에서. 없는 기록(옛 회차)은 조용히 건너뛴다.
  [ -f "$2" ] && return 0
  [ -n "$BUCKET" ] || BUCKET=$(aws ssm get-parameter --region "$REGION" --name "$SSM_PREFIX/app.storage.bucket" --query Parameter.Value --output text)
  aws s3 cp --only-show-errors --region "$REGION" "s3://$BUCKET/load-results/$REPORT_LABEL/$1" "$2" 2>/dev/null || [ "${3:-}" = optional ]
}
fetch requests.csv.gz "$CSV_PATH"
fetch stimulus.env "$STIM_PATH"
for f in events.tsv targets.tsv servers.txt; do fetch "$f" "$EXTRA-$f" optional; done

TABLES=$(mktemp -d -t a0-gap.XXXXXX); trap 'rm -rf "$TABLES"' EXIT
python3 "$(dirname "$0")/gap.py" "$CSV_PATH" "$STIM_PATH" "$TABLES" "$EXTRA-events.tsv" "$EXTRA-targets.tsv"
# shellcheck disable=SC1091
source "$TABLES/verdict.env"

DB_NAME="DB 안 씀"
report_begin "배포·장애 구간의 초 단위 실패 타임라인" "k6 요청 CSV $(basename "$CSV_PATH") · 자극 $KIND"

report_section "1. 요약" "실패 = 응답 없음 또는 5xx · F = 첫 실패 · T1 = 그 뒤 성공만 10초 이어진 시작 · 자극 구간 = [T0−10초, max(T1, 자극 끝)+30초] · watch 는 부하 전체"
cat "$TABLES/summary.txt"
report_section "2. 공백 (성공 응답이 하나도 없는 연속 초, 긴 순 10개)"
cat "$TABLES/gaps.txt"
report_section "3. 사건 (ASG 활동 · 배포 명령 · 컨테이너 · kill · 리더)" "T0+ = T0 에서 몇 초 뒤"
cat "$TABLES/events.txt"
report_section "4. 대상 이탈 (ALB 대상 그룹 2초 표본에서 healthy → 다른 상태)" "배포의 standby(draining)·kill 한 서버는 예상된 이탈이다"
cat "$TABLES/drops.txt"
if [ -f "$EXTRA-servers.txt" ]; then
  report_section "5. 서버별 (이 회차 구간의 GC 멈춤 · 끝날 때 메모리)" "GC = gc.log 의 Pause 들 · 리더 임대 = 마지막 얻음·잃음 로그"
  column -t -s $'\t' "$EXTRA-servers.txt" | sed 's/^/    /'
fi
report_section "6. 초별 (실패·사건·대상 수 변화 앞뒤 3초는 펼치고, 조용한 초는 접음)" "대상 = healthy 수 / 대상 그룹 서버 수 · 코드 0 = 응답 없음(연결 오류·타임아웃)"
cat "$TABLES/seconds.txt"

report_section "7. 판정 (01 시나리오 · ADR-0001 §6)"
if [ "$KIND" = "watch" ]; then
  if [ -n "$TARGET_DROPS" ]; then
    [ "$TARGET_DROPS" -eq 0 ] && v=pass || v=fail; report_verdict "$v" "대상 이탈 0" "${TARGET_DROPS}번"
  else
    report_verdict info "대상 이탈 0" "대상 표본 없음"
  fi
  if [ -n "$GC_MAX_MS" ]; then
    [ "$GC_MAX_MS" -lt 3000 ] && v=pass || v=fail; report_verdict "$v" "GC 최대 멈춤 < 3초" "${GC_MAX_MS}ms"
  fi
  awk "BEGIN{exit !($WINDOW_RATE < 1)}" && v=pass || v=fail; report_verdict "$v" "실패율 < 1% (부하 전체)" "${WINDOW_RATE}%"
  report_verdict info "가장 긴 공백 · 5초 넘는 공백" "${LONGEST_GAP}초 · ${GAPS_OVER5}개"
elif [ "$KIND" = "deploy" ]; then
  awk "BEGIN{exit !($WINDOW_RATE < 0.1)}" && v=pass || v=fail; report_verdict "$v" "QA-1 자극 구간 실패율 < 0.1%" "${WINDOW_RATE}%"
  [ "$GAPS_OVER5" -eq 0 ] && v=pass || v=fail; report_verdict "$v" "QA-1 5초 넘는 공백 0" "${GAPS_OVER5}개 (가장 긴 것 ${LONGEST_GAP}초)"
  report_verdict info "회복 T0 → T1" "${RECOVER:-회복 못 함}${RECOVER:+초}"
elif [ "$KIND" = "kill" ]; then
  if [ -n "$RECOVER" ]; then
    [ "$RECOVER" -le 30 ] && v=pass || v=fail; report_verdict "$v" "QA-2 회복 T0 → T1 ≤ 30초" "${RECOVER}초"
  elif [ "${DATA_SHORT:-0}" = "1" ]; then
    report_verdict info "회복 T0 → T1 ≤ 30초" "판정 불가 — 첫 실패 뒤 부하가 10초 안에 끝남(자극이 너무 늦음)"
  else
    report_verdict fail "회복 T0 → T1 ≤ 30초" "회복 못 함"
  fi
  [ "${AFTER_T1:-1}" = "0" ] && v=pass || v=fail; report_verdict "$v" "QA-2 T1 뒤 실패 0" "${AFTER_T1:-—}"
  report_verdict info "자극 구간 실패율 · 가장 긴 공백" "${WINDOW_RATE}% · ${LONGEST_GAP}초"
else
  # 자극 기록이 깨졌으면 아무 기준으로나 판정하지 않는다.
  echo "알 수 없는 KIND: '$KIND' — $STIM_PATH 를 확인하라" >&2; exit 1
fi
report_note "원본: $CSV_PATH · $STIM_PATH"
report_end
