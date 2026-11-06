"""k6 요청 CSV + 자극 기록 → 초 단위 실패 타임라인 (#313 02 §2 측정 정의).

    python3 gap.py <requests.csv.gz> <stimulus.env> <출력 폴더> [events.tsv] [targets.tsv]

출력 폴더에 표(summary.txt · gaps.txt · seconds.txt · events.txt · drops.txt)와 판정 값(verdict.env)을 쓴다. 화면 출력은 gap.sh 가 한다.

정의
- 실패 = 응답 없음(status 0) 또는 5xx. 4xx 는 업무 응답이라 실패로 세지 않는다(담기·빼기 404·409 포함).
- F  = T0 − 10초부터 처음 실패가 난 초. 없으면 끊김 없음.
- T1 = F 뒤 처음으로 '성공이 있고 실패가 0인 초'가 10초 이어지기 시작한 초. 없으면 끝까지 회복 못 함.
- 자극 구간 = [T0 − 10, max(T1, 자극 끝) + 30]. 자극 끝(ENDED_AT)은 롤링 배포가 다 끝난 시각, kill 은 대상이 다 돌아와 slow start 까지 —
  서버가 여럿이면 실패 없이 지나가도 그 사이가 구간이어야 한다. 이 구간의 실패율이 QA-1 값.
- 공백 = 성공 응답이 하나도 없는 연속 초(부하 유지 구간 안에서만 — 올리고 내리는 끝은 뺀다).
- 대상 이탈 = ALB 대상 그룹 표본(2초)에서 한 서버가 healthy 에서 다른 상태로 바뀐 것. watch(자극 없음)는 0 이어야 한다(A-2m).
"""
import csv
import gzip
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9))
RECOVER_SECONDS = 10
PRE, POST = 10, 30
# 초별 표에서 실패·사건 앞뒤로 펼쳐 보일 초. 그 밖의 조용한 초는 한 줄로 접는다.
CONTEXT = 3

EVENT_NAMES = {
    "standby": "ALB 에서 뺌(standby)",
    "standby_done": "standby 됨",
    "deploy_command": "배포 명령",
    "container_started": "새 컨테이너",
    "app_ready": "앱 준비(Tomcat)",
    "in_service": "복귀(standby 해제)",
    "in_service_done": "복귀 끝",
    "killed_leader": "kill -9 (리더)",
    "killed_follower": "kill -9",
    "leader_acquired": "리더 넘겨받음",
    "terminating": "ASG 종료 시작",
    "terminating_done": "종료 끝",
    "launching": "ASG 새 서버",
    "launching_done": "새 서버 InService",
    "all_targets_healthy": "대상 전부 healthy",
}


def kst(ts):
    return datetime.fromtimestamp(ts, KST).strftime("%H:%M:%S")


def short(instance):
    return instance if instance == "-" else instance[-5:]


def read_env(path):
    env = {}
    for line in open(path):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            env[k] = v
    return env


def failed(status):
    return status == 0 or status >= 500


def table(rows):
    widths = [max(len(str(r[i])) for r in rows) for i in range(len(rows[0]))]
    out = []
    for j, r in enumerate(rows):
        out.append("    " + "  ".join(str(c).rjust(widths[i]) for i, c in enumerate(r)))
        if j == 0:
            out.append("    " + "  ".join("─" * w for w in widths))
    return "\n".join(out) + "\n"


def read_events(path):
    events = []
    if path and os.path.exists(path):
        for line in open(path):
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3 and parts[0]:
                events.append((float(parts[0]), parts[1], parts[2]))
    return sorted(events)


def read_targets(path):
    """[(epoch, {instance: state})] — 표본 시각순."""
    samples = []
    if path and os.path.exists(path):
        for line in open(path):
            at, _, rest = line.rstrip("\n").partition("\t")
            if not at:
                continue
            states = dict(p.split(":", 1) for p in rest.split() if ":" in p)
            samples.append((float(at), states))
    return sorted(samples, key=lambda s: s[0])


def main(csv_path, env_path, out_dir, events_path=None, targets_path=None):
    total, fails, codes = Counter(), Counter(), defaultdict(Counter)
    opener = gzip.open if csv_path.endswith(".gz") else open
    with opener(csv_path, "rt", newline="") as f:
        for row in csv.DictReader(f):
            if row["metric_name"] != "http_reqs":
                continue
            ts = int(float(row["timestamp"]))
            status = int(row["status"] or 0)
            total[ts] += 1
            if failed(status):
                fails[ts] += 1
                codes[ts][status] += 1
    if not total:
        sys.exit("CSV 에 http_reqs 가 없다")

    env = read_env(env_path)
    kind = env.get("KIND", "")
    t0 = float(env["T0"])
    t0s = int(t0)
    first, last = min(total), max(total)
    ok = {s: total[s] - fails[s] for s in range(first, last + 1)}
    ended = float(env["ENDED_AT"]) if env.get("ENDED_AT") else None
    events = read_events(events_path)
    targets = read_targets(targets_path)

    # F · T1 — watch(자극 없음)는 부하 시작부터 본다.
    f_from = first if kind == "watch" else max(first, t0s - PRE)
    f_sec = next((s for s in range(f_from, last + 1) if fails[s] > 0), None)
    t1 = None
    if f_sec is None:
        t1 = t0s
    else:
        streak = 0
        for s in range(f_sec + 1, last + 1):
            clean = ok[s] > 0 and fails[s] == 0
            streak = streak + 1 if clean else 0
            if streak == RECOVER_SECONDS:
                t1 = s - RECOVER_SECONDS + 1
                break

    if kind == "watch":
        w_start, w_end = first, last
    else:
        w_start = t0s - PRE
        w_end = min(last, max(t1 if t1 is not None else last, int(ended or 0)) + POST)
    w_total = sum(total[s] for s in range(w_start, w_end + 1))
    w_fail = sum(fails[s] for s in range(w_start, w_end + 1))
    w_codes = Counter()
    for s in range(w_start, w_end + 1):
        w_codes.update(codes[s])
    after = sum(fails[s] for s in range((t1 or last) + 1, last + 1)) if t1 is not None else None
    outside = sum(fails[s] for s in range(first, last + 1) if not (w_start <= s <= w_end))
    outside_total = sum(total[s] for s in range(first, last + 1) if not (w_start <= s <= w_end))

    # 공백 — 유지 구간만(처음 40초 올림 · 마지막 15초 내림 제외)
    gaps, run = [], None
    for s in range(first + 40, last - 15 + 1):
        if ok[s] == 0:
            run = [s, s] if run is None else [run[0], s]
        elif run is not None:
            gaps.append(run)
            run = None
    if run is not None:
        gaps.append(run)
    gaps.sort(key=lambda g: g[1] - g[0], reverse=True)
    longest = (gaps[0][1] - gaps[0][0] + 1) if gaps else 0
    over5 = sum(1 for g in gaps if g[1] - g[0] + 1 > 5)

    # 대상 그룹 — 초마다 그 순간의 healthy 수 / 전체, 그리고 healthy 에서 벗어난 것(이탈).
    healthy_at, changes, drops = {}, set(), []
    prev = {}
    idx = 0
    for s in range(first, last + 1):
        while idx < len(targets) and targets[idx][0] < s + 1:
            at, states = targets[idx]
            for inst, state in states.items():
                if prev.get(inst) == "healthy" and state != "healthy":
                    drops.append((at, inst, state))
                if prev.get(inst) != state and inst in prev:
                    changes.add(s)
            prev = states
            idx += 1
        if prev:
            healthy_at[s] = f"{sum(1 for v in prev.values() if v == 'healthy')}/{len(prev)}"

    started = float(env["CONTAINER_STARTED_AT"]) if env.get("CONTAINER_STARTED_AT") else None
    healthy = float(env["HEALTHY_AT"]) if env.get("HEALTHY_AT") else None

    def rel(ts):
        return "—" if ts is None else f"{kst(ts)} (T0{round(ts - t0):+d}초)"

    rows = [("칸", "값")]
    rows.append(("자극", kind + (f" · {env['TARGET_ID']} ({env.get('TARGET_ROLE', '')})" if env.get("TARGET_ID") else "")))
    rows.append(("누른 시각", rel(float(env["PRESSED_AT"])) if env.get("PRESSED_AT") else "—"))
    rows.append(("T0", kst(t0)))
    rows.append(("자극 끝", rel(ended)))
    rows.append(("새 컨테이너 시작(첫 서버)", rel(started)))
    rows.append(("앱 준비(첫 서버)", rel(healthy)))
    rows.append(("첫 실패 F", rel(f_sec)))
    # 첫 실패 뒤 부하가 10초도 안 남았으면 회복을 못 잰 것이지 회복 못 한 것이 아니다(자극을 너무 늦게 준 회차).
    data_short = t1 is None and f_sec is not None and last - f_sec < RECOVER_SECONDS
    rows.append(("회복 T1", rel(t1) if t1 is not None else ("판정 불가 (F 뒤 데이터 부족)" if data_short else "회복 못 함")))
    rows.append(("끊긴 길이 T1 − F", "—" if f_sec is None or t1 is None else f"{t1 - f_sec}초"))
    rows.append(("자극 구간" if kind != "watch" else "판정 구간(부하 전체)", f"{kst(w_start)} ~ {kst(w_end)} ({w_end - w_start + 1}초)"))
    rows.append(("구간 요청 · 실패", f"{w_total:,} · {w_fail:,} ({w_fail / w_total * 100 if w_total else 0:.3f}%)"))
    rows.append(("실패 응답 코드", " · ".join(f"{'응답 없음' if c == 0 else c} {n:,}" for c, n in w_codes.most_common()) or "없음"))
    if kind != "watch":
        rows.append(("구간 밖 실패", f"{outside:,} / {outside_total:,}"))
        rows.append(("T1 뒤 실패", "—" if after is None else f"{after:,}"))
    rows.append(("가장 긴 공백 · 5초 넘는 공백", f"{longest}초 · {over5}개"))
    rows.append(("대상 이탈(healthy → 다른 상태)", f"{len(drops)}번" if targets else "표본 없음"))
    if env.get("GC_MAX_MS"):
        rows.append(("GC 최대 멈춤(서버 중 최대)", f"{int(env['GC_MAX_MS']):,}ms"))
    rows.append(("전체 요청", f"{sum(total.values()):,} ({kst(first)} ~ {kst(last)})"))
    open(f"{out_dir}/summary.txt", "w").write(table(rows))

    g_rows = [("시작", "끝", "길이")] + [(kst(a), kst(b), f"{b - a + 1}초") for a, b in gaps[:10]]
    open(f"{out_dir}/gaps.txt", "w").write(table(g_rows) if gaps else "    공백 없음\n")

    e_rows = [("시각", "T0+", "서버", "사건")] + [(kst(at), f"{round(at - t0):+d}", short(inst), EVENT_NAMES.get(name, name))
                                            for at, inst, name in events]
    open(f"{out_dir}/events.txt", "w").write(table(e_rows) if events else "    사건 기록 없음\n")
    d_rows = [("시각", "T0+", "서버", "바뀐 상태")] + [(kst(at), f"{round(at - t0):+d}", short(inst), state) for at, inst, state in drops]
    open(f"{out_dir}/drops.txt", "w").write(table(d_rows) if drops else "    이탈 없음\n")

    # 초별 — 실패·사건·대상 수가 바뀐 초는 앞뒤 CONTEXT 초까지 펼치고, 나머지 조용한 초는 한 줄로 접는다.
    by_sec = defaultdict(list)
    for at, inst, name in events:
        by_sec[int(at)].append(f"{short(inst)} {EVENT_NAMES.get(name, name)}" if inst != "-" else EVENT_NAMES.get(name, name))
    if kind == "watch":
        lo, hi = first, last
    else:
        lo = min(f_sec if f_sec is not None else t0s, t0s) - 5
        hi = max(w_end, (t1 or t0s) + 5)
    lo, hi = max(first, lo), min(last, hi)
    marks = {s for s in range(lo, hi + 1) if fails[s] > 0 or s in by_sec or s in changes}
    shown = {s + d for s in marks for d in range(-CONTEXT, CONTEXT + 1)}
    s_rows = [("초", "T0+", "대상", "요청", "성공", "실패", "실패율", "코드", "사건")]
    s = lo
    while s <= hi:
        if s in shown or s == lo or s == hi:
            n = total[s]
            s_rows.append((kst(s), s - t0s, healthy_at.get(s, "—"), n, ok[s], fails[s], f"{fails[s] / n * 100:.0f}%" if n else "—",
                           " ".join(f"{'0' if c == 0 else c}×{k}" for c, k in codes[s].most_common(3)), " · ".join(by_sec.get(s, []))))
            s += 1
            continue
        e = s
        while e + 1 <= hi and e + 1 not in shown and e + 1 != hi:
            e += 1
        if e == s:
            shown.add(s)
            continue
        n = sum(total[x] for x in range(s, e + 1))
        k = sum(ok[x] for x in range(s, e + 1))
        s_rows.append((f"{kst(s)}~{kst(e)}", f"{s - t0s}~{e - t0s}", healthy_at.get(e, "—"), n, k, 0, "0%", f"({e - s + 1}초 접음)", ""))
        s = e + 1
    open(f"{out_dir}/seconds.txt", "w").write(table(s_rows))

    with open(f"{out_dir}/verdict.env", "w") as v:
        v.write(f"KIND={kind}\n")
        v.write(f"WINDOW_RATE={w_fail / w_total * 100 if w_total else 0:.3f}\n")
        v.write(f"LONGEST_GAP={longest}\n")
        v.write(f"GAPS_OVER5={over5}\n")
        v.write(f"RECOVER={'' if t1 is None else max(0, t1 - t0s)}\n")
        v.write(f"AFTER_T1={'' if after is None else after}\n")
        v.write(f"DATA_SHORT={1 if data_short else 0}\n")
        v.write(f"TARGET_DROPS={len(drops) if targets else ''}\n")
        v.write(f"GC_MAX_MS={env.get('GC_MAX_MS', '')}\n")


if __name__ == "__main__":
    main(*sys.argv[1:6])
