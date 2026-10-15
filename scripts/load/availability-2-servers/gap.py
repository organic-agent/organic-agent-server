"""k6 요청 CSV + 자극 기록 → 초 단위 실패 타임라인 (#313 02 §2 측정 정의).

    python3 gap.py <requests.csv.gz> <stimulus.env> <출력 폴더>

출력 폴더에 표 세 개(summary.txt · gaps.txt · seconds.txt)와 판정 값(verdict.env)을 쓴다. 화면 출력은 gap.sh 가 한다.

정의
- 실패 = 응답 없음(status 0) 또는 5xx. 4xx 는 업무 응답이라 실패로 세지 않는다(담기·빼기 404·409 포함).
- F  = T0 − 10초부터 처음 실패가 난 초. 없으면 끊김 없음.
- T1 = F 뒤 처음으로 '성공이 있고 실패가 0인 초'가 10초 이어지기 시작한 초. 없으면 끝까지 회복 못 함.
- 자극 구간 = [T0 − 10, T1 + 30]. 이 구간의 실패율이 QA-1 값.
- 공백 = 성공 응답이 하나도 없는 연속 초(부하 유지 구간 안에서만 — 올리고 내리는 끝은 뺀다).
"""
import csv
import gzip
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9))
RECOVER_SECONDS = 10
PRE, POST = 10, 30


def kst(ts):
    return datetime.fromtimestamp(ts, KST).strftime("%H:%M:%S")


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


def main(csv_path, env_path, out_dir):
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
    t0 = float(env["T0"])
    t0s = int(t0)
    first, last = min(total), max(total)
    ok = {s: total[s] - fails[s] for s in range(first, last + 1)}

    # F · T1
    f_sec = next((s for s in range(max(first, t0s - PRE), last + 1) if fails[s] > 0), None)
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

    w_start = t0s - PRE
    w_end = (t1 if t1 is not None else last) + POST
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

    started = float(env["CONTAINER_STARTED_AT"]) if env.get("CONTAINER_STARTED_AT") else None
    healthy = float(env["HEALTHY_AT"]) if env.get("HEALTHY_AT") else None

    def rel(ts):
        return "—" if ts is None else f"{kst(ts)} (T0{ts - t0:+.0f}초)"

    rows = [("칸", "값")]
    rows.append(("자극", env.get("KIND", "?")))
    rows.append(("누른 시각", rel(float(env["PRESSED_AT"])) if env.get("PRESSED_AT") else "—"))
    rows.append(("T0", kst(t0)))
    rows.append(("새 컨테이너 시작", rel(started)))
    rows.append(("컨테이너 healthy", rel(healthy)))
    rows.append(("첫 실패 F", rel(f_sec)))
    rows.append(("회복 T1", rel(t1) if t1 is not None else "회복 못 함"))
    rows.append(("끊긴 길이 T1 − F", "—" if f_sec is None or t1 is None else f"{t1 - f_sec}초"))
    rows.append(("자극 구간", f"{kst(w_start)} ~ {kst(w_end)} ({w_end - w_start + 1}초)"))
    rows.append(("자극 구간 요청 · 실패", f"{w_total:,} · {w_fail:,} ({w_fail / w_total * 100 if w_total else 0:.2f}%)"))
    rows.append(("실패 응답 코드", " · ".join(f"{'응답 없음' if c == 0 else c} {n:,}" for c, n in w_codes.most_common()) or "없음"))
    rows.append(("구간 밖 실패", f"{outside:,} / {outside_total:,}"))
    rows.append(("T1 뒤 실패", "—" if after is None else f"{after:,}"))
    rows.append(("가장 긴 공백 · 5초 넘는 공백", f"{longest}초 · {over5}개"))
    rows.append(("전체 요청", f"{sum(total.values()):,} ({kst(first)} ~ {kst(last)})"))
    open(f"{out_dir}/summary.txt", "w").write(table(rows))

    g_rows = [("시작", "끝", "길이")] + [(kst(a), kst(b), f"{b - a + 1}초") for a, b in gaps[:10]]
    open(f"{out_dir}/gaps.txt", "w").write(table(g_rows) if gaps else "    공백 없음\n")

    lo = (f_sec if f_sec is not None else t0s) - 5
    hi = (t1 if t1 is not None else min(last, t0s + 120)) + 5
    s_rows = [("초", "T0+", "요청", "성공", "실패", "실패율", "코드")]
    for s in range(max(first, lo), min(last, hi) + 1):
        n = total[s]
        s_rows.append((kst(s), s - t0s, n, ok[s], fails[s], f"{fails[s] / n * 100:.0f}%" if n else "—",
                       " ".join(f"{'0' if c == 0 else c}×{k}" for c, k in codes[s].most_common(3))))
    open(f"{out_dir}/seconds.txt", "w").write(table(s_rows))

    with open(f"{out_dir}/verdict.env", "w") as v:
        v.write(f"KIND={env.get('KIND', '')}\n")
        v.write(f"WINDOW_RATE={w_fail / w_total * 100 if w_total else 0:.3f}\n")
        v.write(f"LONGEST_GAP={longest}\n")
        v.write(f"GAPS_OVER5={over5}\n")
        v.write(f"RECOVER={'' if t1 is None else max(0, t1 - t0s)}\n")
        v.write(f"AFTER_T1={'' if after is None else after}\n")


if __name__ == "__main__":
    main(*sys.argv[1:4])
