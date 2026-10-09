#!/usr/bin/env bash
# 실험 E-SPLIT — dev GPU 1대에서 점수 워커를 설정 4가지(V0~V3)로 같은 원본 사진에 돌려 처리량·단계 시간·CLIP 품질을 비교한다. dev 전용(쓰기).
# 설계: docs/plans/2026-10-08/upload-5x-10min/04-exp-score-split.md · ADR 0002.
#
#   scripts/load/upload-5x-10min/exp-score-split.sh --label E-SPLIT-1 --pipeline <실험 브랜치의 score/score/service/pipeline.py> [--source 20] [--batches 60]
#
# 순서: GPU 켜기 → 기본 워커 유닛 멈춤 → 갤러리 SOURCE를 점수만 비운 채 4개 복제 → 군마다 컨테이너 1회(MAX_BATCHES) → 그 군이 못 먹은 행은 지움
#       → 로그 회수·요약 → V1·V3 CLIP 코사인·피사체 일치 → 복제 갤러리 삭제 + VACUUM → GPU 정지.
# 영향: dev DB 복제 행(사진 4 × N, 끝나면 삭제), dev GPU ≈ 15~20분(≈ $0.3), S3 GET. 운영 무관. 실험 코드는 볼륨으로 덮어 이미지·AMI 안 건드림.
# 중단: Ctrl+C → 트랩이 복제 갤러리 삭제 + GPU 정지를 시도한다. 실패하면 수동: 갤러리 삭제(title LIKE '%(부하 %'), aws ec2 stop-instances.
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/load/lib/report.sh
report_parse_common dev "$@"; set -- ${REPORT_REST[@]+"${REPORT_REST[@]}"}
SOURCE=20; BATCHES=60; PIPELINE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift ;;
    --batches) BATCHES="$2"; shift ;;
    --pipeline) PIPELINE="$2"; shift ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done
[ -f "$PIPELINE" ] || { echo "--pipeline 실험 pipeline.py 경로가 필요하다" >&2; exit 1; }
report_connect
write_sql() {
  PGPASSWORD="$DB_PASSWORD" docker run --rm -i -e PGPASSWORD pgvector/pgvector:pg16 \
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -X -At "$@"
}
INSTANCE=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Name,Values=$GPU_TAG_NAME" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
ssm_run() {  # ssm_run <timeout초> <명령…> — 끝날 때까지(최대 timeout+60초) 기다리고 stdout 을 찍는다. 보내기 실패·시간 초과는 1
  local timeout="$1"; shift
  local params; params=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.argv[2:], "executionTimeout": [sys.argv[1]]}))' "$timeout" "$@")
  local cid; cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
    --parameters "$params" --timeout-seconds 60 --query Command.CommandId --output text 2>/dev/null) || cid=""
  [ -n "$cid" ] && [ "$cid" != "None" ] || { echo "SSM 보내기 실패" >&2; return 1; }
  local st="" deadline=$(( $(date +%s) + timeout + 60 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$INSTANCE" --query Status --output text 2>/dev/null || echo Pending)
    case "$st" in Success|Failed|TimedOut|Cancelled|Undeliverable|Terminated) break ;; esac
    sleep 5
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$INSTANCE" --query StandardOutputContent --output text 2>/dev/null || true
  [ "$st" = "Success" ] || { echo "SSM 명령 ${st:-응답 없음}" >&2; return 1; }
}
CLONES=""
# 군마다 "의도한 복제 갤러리만 점수가 났나"를 본다. 집기가 gallery_id 순이라 앞 갤러리부터 먹는다는 가정에 기대므로
# (워커 집기 순서가 바뀌면 깨진다) 다음 갤러리에 점수가 새면 측정을 멈춘다.
check_arm() {  # check_arm <이 군의 갤러리> <다음 갤러리들(쉼표, 없으면 빈 값)>
  local mine leaked
  mine=$(report_scalar -c "SELECT count(*) FROM photos p JOIN photo_analysis a ON a.photo_id = p.id WHERE p.gallery_id = $1 AND a.clip_embedding IS NOT NULL")
  leaked=0
  [ -n "$2" ] && leaked=$(report_scalar -c "SELECT count(*) FROM photos p JOIN photo_analysis a ON a.photo_id = p.id WHERE p.gallery_id IN ($2) AND a.clip_embedding IS NOT NULL")
  report_note "갤러리 $1 점수 ${mine}장 · 다음 갤러리로 샌 점수 ${leaked}장"
  [ "$leaked" -eq 0 ] || { echo "다음 갤러리($2)에 점수 ${leaked}장이 났다 — 군이 섞였다" >&2; exit 1; }
}
cleanup() {
  trap - INT TERM EXIT
  if [ -n "$CLONES" ]; then
    echo "정리: 복제 갤러리 $CLONES 삭제"
    write_sql -c "DELETE FROM galleries WHERE id IN ($CLONES) AND title LIKE '%(부하 %'" || true
    write_sql -c "VACUUM (ANALYZE) photo_analysis" -c "VACUUM (ANALYZE) photos" || true
  fi
  aws ec2 stop-instances --region "$REGION" --instance-ids "$INSTANCE" >/dev/null 2>&1 && echo "정리: GPU $INSTANCE 정지 요청" || true
}
trap cleanup INT TERM EXIT

report_begin "실험 E-SPLIT — 점수 워커 설정별 처리량" "갤러리 $SOURCE 복제 4개 · 군마다 ${BATCHES}배치(×32장) · GPU $INSTANCE"

report_section "1. 사진 준비" "갤러리 $SOURCE 를 점수만 비운 채 4개 복제 (같은 S3 미리보기, 업로드·임베딩 없음) — 할 일이 있어야 워커가 스스로 꺼지지 않는다"
CLONE_AT=$(date +%s)
CLONE_OUT="${REPORT_FILE%.txt}-clone.txt"
# 복제 도구가 출력한 CLONED_GALLERY=<id> 만 정리 대상으로 쓴다 — 제목으로 추측하면 다른 갤러리를 지울 수 있고, 중간 실패 때 빠진다.
clone_rc=0
scripts/load/clone-gallery-photos.sh dev --source "$SOURCE" --count 4 --strip score --yes > "$CLONE_OUT" 2>&1 || clone_rc=$?
sed 's/^/    /' "$CLONE_OUT"
CLONES=$(grep -oE 'CLONED_GALLERY=[0-9]+' "$CLONE_OUT" | cut -d= -f2 | paste -sd, - || true)
report_note "복제 갤러리: ${CLONES:-없음}"
[ "$clone_rc" -eq 0 ] && [ "$(tr ',' '\n' <<< "$CLONES" | grep -c .)" -eq 4 ] \
  || { echo "복제 실패(exit $clone_rc, 만든 갤러리 ${CLONES:-0개}) — 만든 것만 정리하고 멈춘다" >&2; exit 1; }
IFS=',' read -r -a CLONE_IDS <<< "$CLONES"
LOG_DIR="${REPORT_FILE%.txt}-logs"; mkdir -p "$LOG_DIR"

report_section "2. GPU 켜기 · V0 = 기본 워커(지금 배포 그대로)" "wes 스윕이 먼저 켤 수도 있다 — 켜져 있으면 그대로"
STARTED_AT=$(date +%s)
state=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE" --query 'Reservations[0].Instances[0].State.Name' --output text)
case "$state" in stopped) aws ec2 start-instances --region "$REGION" --instance-ids "$INSTANCE" >/dev/null ;; stopping) aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$INSTANCE"; aws ec2 start-instances --region "$REGION" --instance-ids "$INSTANCE" >/dev/null ;; esac
aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCE"
for _ in $(seq 1 90); do
  ping=$(aws ssm describe-instance-information --region "$REGION" --filters Key=InstanceIds,Values="$INSTANCE" --query 'InstanceInformationList[0].LastPingDateTime' --output text 2>/dev/null || echo 0)
  ping_s=$(python3 -c 'import sys,datetime
try: print(int(datetime.datetime.fromisoformat(sys.argv[1]).timestamp()))
except Exception: print(0)' "$ping")
  [ "$ping_s" -gt "$STARTED_AT" ] && break; sleep 2
done
report_note "SSM 연결: $ping"
# 우리가 유닛을 멈출 때 failed 가 되어도 인스턴스를 끄지 않게 failsafe 를 이번 부팅 동안만 무력화한다.
# mask --runtime 은 안 듣는다(유닛 파일이 /etc 에 있고 /etc 가 /run 보다 우선). /run 드롭인은 병합이라 듣고, 재부팅하면 사라진다.
ssm_run 60 "mkdir -p /run/systemd/system/wes-score-failsafe.service.d && printf '[Service]\\nExecStart=\\nExecStart=/bin/true\\n' > /run/systemd/system/wes-score-failsafe.service.d/exp.conf && systemctl daemon-reload && systemctl show -p ExecStart wes-score-failsafe.service | cut -c1-120; systemctl is-active wes-score.service || true" | sed 's/^/    /'
need=$((BATCHES + 3)); n=0
# 워커 로그는 유닛 이름(-u)으로 안 잡힐 수 있다 — 이번 부팅 journal 에서, 복제 이후(--since) 기록만 센다(dev 앱이 GPU 를 먼저 켰을 수 있다). 6분 안에 못 채우면 중단(복제 사진을 V0이 다 먹지 않게).
v0_deadline=$(( $(date +%s) + 360 ))
while [ "$(date +%s)" -lt "$v0_deadline" ]; do
  n=$(ssm_run 30 "journalctl -b --since @$CLONE_AT --no-pager -o cat | grep -cE '\\[worker\\] 배치 [0-9]+장' || true" 2>/dev/null | tr -dc '0-9'); n=${n:-0}
  [ "$n" -ge "$need" ] && break; sleep 5
done
[ "$n" -ge "$need" ] || { echo "V0 배치를 6분 안에 ${need}개 못 셌다 (센 값 ${n})" >&2; exit 1; }
report_note "V0 배치 ${n}개 — 기본 워커를 정상 종료한다"
# 정상 종료를 확인해야 다음 군이 GPU 를 혼자 쓴다 — inactive·failed(드롭인으로 failsafe 무력화) 말고는 멈춘다.
ssm_run 200 "mkdir -p /tmp/exp; journalctl -b --since @$CLONE_AT --no-pager -o cat > /tmp/exp/V0.log; systemctl stop wes-score.service; st=\$(systemctl is-active wes-score.service); echo \"wes-score: \$st\"; case \$st in inactive|failed) ;; *) exit 1 ;; esac" | sed 's/^/    /'
G_V0=${CLONE_IDS[0]}
check_arm "$G_V0" "$(IFS=,; echo "${CLONE_IDS[*]:1}")"
write_sql -c "UPDATE photos p SET deleted_at = now() FROM photo_analysis a WHERE a.photo_id = p.id AND p.gallery_id = $G_V0 AND a.clip_embedding IS NULL AND p.deleted_at IS NULL" >/dev/null
ssm_run 60 "grep -E '배치 [0-9]+장|/장|러너 로드|ERROR|Traceback' /tmp/exp/V0.log" > "$LOG_DIR/V0.log"
PIPE_B64=$(base64 < "$PIPELINE" | tr -d '\n')
ssm_run 120 "echo $PIPE_B64 | base64 -d > /tmp/exp/pipeline.py && wc -c /tmp/exp/pipeline.py" \
  "/usr/local/bin/wes-score-env.sh && . /etc/wes-score/image.env && echo image=\$WES_SCORE_IMAGE" | sed 's/^/    /'

declare -a ARMS=(
  "V1|-e CLIP_BATCH=32 -e ARNIQA_BATCH=8 -e SCORE_DECODE_WORKERS=4 -e SCORE_DOWNLOAD_WORKERS=16 -e SCORE_FP16=1"
  "V2|-e CLIP_BATCH=32 -e ARNIQA_BATCH=8 -e SCORE_DECODE_WORKERS=4 -e SCORE_DOWNLOAD_WORKERS=16 -e SCORE_FP16=1 -e SCORE_SKIP_ARNIQA=1 -e SCORE_SKIP_CLASSICAL=1"
  "V3|-e CLIP_BATCH=32 -e ARNIQA_BATCH=8 -e SCORE_DECODE_WORKERS=4 -e SCORE_DOWNLOAD_WORKERS=16 -e SCORE_FP16=1 -e SCORE_SKIP_ARNIQA=1 -e SCORE_SKIP_CLASSICAL=1 -e SCORE_CLIP_DRAFT=1"
)
i=1
for arm in "${ARMS[@]}"; do
  name=${arm%%|*}; envs=${arm#*|}; gid=${CLONE_IDS[$i]}; i=$((i + 1)); eval "G_$name=$gid"
  report_section "3-$name. 워커 실행" "갤러리 $gid 의 앞 $((BATCHES * 32))장 · ${envs:-기본값}"
  ssm_run 900 ". /etc/wes-score/image.env; docker run --rm --name exp-$name --gpus all --env-file /run/wes-score.env -e DB_USER=photoselect -e DB_SSLMODE=verify-full -e DB_SSLROOTCERT=/opt/rds-ca/global-bundle.pem -e WORKER_IDLE_STOP_SECONDS=0 -e WORKER_MAX_BATCHES=$BATCHES $envs -v /tmp/exp/pipeline.py:/app/score/service/pipeline.py:ro \$WES_SCORE_IMAGE > /tmp/exp/$name.log 2>&1; rc=\$?; echo exit=\$rc; grep -E '러너 로드|실험 E-SPLIT' /tmp/exp/$name.log | cut -c1-200; grep -E '배치 [0-9]+장' /tmp/exp/$name.log | tail -2 | cut -c1-160; grep -E '/장' /tmp/exp/$name.log | tail -1 | cut -c1-200; [ \$rc -eq 0 ] || { tail -20 /tmp/exp/$name.log; exit \$rc; }" | sed 's/^/    /'
  check_arm "$gid" "$(IFS=,; echo "${CLONE_IDS[*]:$i}")"
  # 이 군이 못 먹은 행은 다음 군이 집지 않게 지운다(다음 군은 다음 복제 갤러리의 앞부분 = 같은 원본 사진).
  write_sql -c "UPDATE photos p SET deleted_at = now() FROM photo_analysis a WHERE a.photo_id = p.id AND p.gallery_id = $gid AND a.clip_embedding IS NULL AND p.deleted_at IS NULL" >/dev/null
  ssm_run 60 "grep -E '배치 [0-9]+장|/장|러너 로드|실험 E-SPLIT|ERROR|Traceback' /tmp/exp/$name.log" > "$LOG_DIR/$name.log"
done

report_section "4. 처리량 · 장당 단계 시간" "배치 32장 시간(첫 3배치 제외) · 단계는 군 마지막 누적 평균(초/장) · 원로그 $LOG_DIR"
python3 - "$LOG_DIR" <<'PY'
import re, statistics, sys, pathlib
d = pathlib.Path(sys.argv[1]); base = None
for name in ["V0", "V1", "V2", "V3"]:
    t = (d / f"{name}.log").read_text()
    xs = [float(x) for x in re.findall(r"배치 \d+장 ([0-9.]+)s", t)][3:]
    stage = (re.findall(r"/장\)\s+(.*)", t) or ["-"])[-1]
    if not xs:
        print(f"    {name}  배치 기록 없음"); continue
    xs.sort(); med = statistics.median(xs); p90 = xs[int(len(xs) * 0.9) - 1]; rate = 32 / med
    base = base or (rate if name == "V1" else None)
    print(f"    {name}  배치 {len(xs)}개  중앙 {med:.2f}s  p90 {p90:.2f}s  →  초당 {rate:5.1f}장   단계 {stage.strip()}")
PY
report_note "프리페치(decode_workers>0)면 decode 는 '준비를 기다린 벽시계' — 단계 합이 장당 시간과 다를 수 있다(코드 주석)"

report_section "6. 품질 — V1 vs V3 같은 원본 사진의 CLIP·피사체"
report_sql -c "
  WITH v1 AS (SELECT p.preview_key, a.clip_embedding c, a.subjects s FROM photos p JOIN photo_analysis a ON a.photo_id = p.id
              WHERE p.gallery_id = $G_V1 AND a.clip_embedding IS NOT NULL),
       v3 AS (SELECT p.preview_key, a.clip_embedding c, a.subjects s FROM photos p JOIN photo_analysis a ON a.photo_id = p.id
              WHERE p.gallery_id = $G_V3 AND a.clip_embedding IS NOT NULL),
       j AS (SELECT 1 - (v1.c <=> v3.c) AS cos, v1.s = v3.s AS same FROM v1 JOIN v3 USING (preview_key))
  SELECT count(*) AS 짝, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY cos)::numeric, 4) AS cos_중앙,
         round(percentile_cont(0.05) WITHIN GROUP (ORDER BY cos)::numeric, 4) AS cos_p5, round(min(cos)::numeric, 4) AS cos_최소,
         round(100.0 * avg(same::int), 1) AS 피사체_일치_pct FROM j"
report_sql -c "
  WITH v1 AS (SELECT p.preview_key, a.clip_embedding c FROM photos p JOIN photo_analysis a ON a.photo_id = p.id WHERE p.gallery_id = $G_V1 AND a.clip_embedding IS NOT NULL),
       v2 AS (SELECT p.preview_key, a.clip_embedding c FROM photos p JOIN photo_analysis a ON a.photo_id = p.id WHERE p.gallery_id = $G_V2 AND a.clip_embedding IS NOT NULL)
  SELECT 'V1 vs V2 (같은 디코드라 1에 가까워야)' AS 비교, count(*) AS 짝, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY 1 - (v1.c <=> v2.c))::numeric, 4) AS cos_중앙
  FROM v1 JOIN v2 USING (preview_key)"

report_section "7. 판정 기준" "04 문서 4장"
report_note "V3 ≥ 64/s 그리고 V3/V1 ≥ 1.3 → B 유력 · V3/V1 < 1.3 → 이득 미미(A) · CLIP cos 중앙 < 0.995 또는 p5 < 0.98 또는 피사체 < 98% → 축소 디코드 제외(V2로 판정)"
report_end
