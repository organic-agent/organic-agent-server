#!/usr/bin/env python3
"""checks.md의 명령을 ID별로 돌려 결과를 남긴다. 저장소 루트에서:

    python3 -I .claude/skills/rule-audit/scripts/run_checks.py <출력 폴더>

- `script` 담당(G·S): 출력 줄이 곧 위반 → <출력>/script-violations.md
- G→J 담당: 출력 줄은 후보 → <출력>/candidates/<ID>.txt (담당 sonnet이 읽는다)
- 담당별 작업 목록 → <출력>/work-<담당>.md (ID · 출처 · 찾는 법 · 후보 파일)
- 허용 목록(reference/allowlist-*.txt)이 있으면 그 줄을 위반에서 빼고, 고쳐져서 더는 안 걸리는 줄을 따로 적는다.
"""
import glob, os, re, subprocess, sys, time

ROOT = os.getcwd()
REF = os.path.join(ROOT, '.claude/skills/rule-audit/reference')
CHECKS = os.path.join(REF, 'checks.md')
ID = r'[A-Z]+-[A-Za-z]?\d+'
ROW = re.compile(r'^\|\s*(' + ID + r')\s*\|\s*([^|]*?)\s*\|\s*(.*?)\s*\|\s*([HTR])\s*\|\s*(\S+)\s*\|\s*$', re.M)
FENCE = re.compile(r'```bash\n(.*?)```', re.S)
TIMEOUT = 300


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def write(p, text):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f:
        f.write(text)


def parse(md):
    rows = {m.group(1): {'source': m.group(2), 'how': m.group(3), 'enforce': m.group(4), 'owner': m.group(5)}
            for m in ROW.finditer(md)}
    commands = {}
    for block in FENCE.findall(md):
        current, lines = None, []
        for line in block.split('\n') + ['']:
            m = re.match(r'^#\s*(' + ID + r')\s*$', line)
            if m:
                if current and lines:
                    commands[current] = '\n'.join(lines).strip()
                current, lines = m.group(1), []
            elif line.strip() == '':
                if current and lines:
                    commands[current] = '\n'.join(lines).strip()
                    current, lines = None, []
            elif current:
                lines.append(line)
    return rows, commands


def allowlists():
    """[(파일 이름, [ID…], [허용 줄 접두사…])] — 첫 줄 `# rules: ID…`, 나머지는 경로 또는 경로:줄:"""
    result = []
    for p in sorted(glob.glob(os.path.join(REF, 'allowlist-*.txt'))):
        lines = read(p).splitlines()
        if not lines:
            continue
        m = re.match(r'#\s*rules:\s*((?:' + ID + r'\s*)+)', lines[0])
        if not m:
            continue
        entries = [l.strip() for l in lines[1:] if l.strip() and not l.startswith('#')]
        result.append((os.path.basename(p), m.group(1).split(), entries))
    return result


def main():
    if len(sys.argv) != 2:
        sys.exit('사용법: run_checks.py <출력 폴더>')
    out = os.path.abspath(sys.argv[1])
    rows, commands = parse(read(CHECKS))
    lists = allowlists()
    allow = {}
    for _, ids, entries in lists:
        for i in ids:
            allow.setdefault(i, []).extend(entries)
    started = time.time()

    script_lines, stale, errors, counts, found_by_id = [], [], [], {}, {}
    work = {}
    for rid, row in rows.items():
        owner = row['owner']
        cmd = commands.get(rid)
        if owner != 'script':
            work.setdefault(owner, []).append((rid, row, bool(cmd)))
        if not cmd:
            continue
        try:
            proc = subprocess.run(['bash', '-c', cmd], cwd=ROOT, capture_output=True, text=True, timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            errors.append(f'{rid}: {TIMEOUT}초 초과')
            continue
        if proc.returncode not in (0, 1):
            errors.append(f'{rid}: 종료 코드 {proc.returncode} — {proc.stderr.strip()[:200]}')
        found = [l for l in proc.stdout.splitlines() if l.strip()]
        entries = allow.get(rid, [])
        kept = [l for l in found if not any(l.startswith(e) for e in entries)]
        found_by_id[rid] = found
        counts[rid] = (len(found), len(kept))
        if owner == 'script':
            script_lines += [f'{rid} {l}' for l in kept]
        else:
            write(os.path.join(out, 'candidates', f'{rid}.txt'), '\n'.join(kept) + ('\n' if kept else ''))

    # 목록 하나를 여러 규칙이 함께 쓰므로, 어느 규칙의 출력에도 걸리지 않는 줄만 "고쳐졌다"로 본다
    for name, ids, entries in lists:
        lines = [l for i in ids for l in found_by_id.get(i, [])]
        stale += [f'{name} {e}' for e in entries if not any(l.startswith(e) for l in lines)]

    elapsed = time.time() - started
    write(os.path.join(out, 'script-violations.md'),
          '# script 위반 (G·S)\n\n형식: `ID 경로:줄 …`\n\n```\n' + '\n'.join(script_lines) + '\n```\n')
    for owner, items in sorted(work.items()):
        body = [f'# {owner} 작업 목록', '', '| ID | 출처 | 찾는 법 | 후보 |', '|---|---|---|---|']
        for rid, row, has_cmd in items:
            cand = f'candidates/{rid}.txt ({counts.get(rid, (0, 0))[1]}줄)' if has_cmd else '— (직접 읽기)'
            body.append(f"| {rid} | {row['source']} | {row['how']} | {cand} |")
        write(os.path.join(out, f'work-{owner}.md'), '\n'.join(body) + '\n')
    summary = [
        '# 실행 요약',
        f'- 규칙 {len(rows)} · 명령 {len(commands)} · 걸린 시간 {elapsed:.1f}초',
        f'- script 위반 {len(script_lines)}줄 · 허용 목록 규칙 {len(allow)}개',
        f'- 담당별 규칙: ' + ' · '.join(f'{o} {len(v)}' for o, v in sorted(work.items())),
        '', '## 명령 오류', *(errors or ['없음']),
        '', '## 고쳐져서 허용 목록에서 지워도 되는 줄', *(stale or ['없음']),
        '', '## ID별 줄 수 (원본 → 허용 목록 뒤)', '',
        *[f'- {i}: {a} → {b}' for i, (a, b) in sorted(counts.items())],
    ]
    write(os.path.join(out, 'run-summary.md'), '\n'.join(summary) + '\n')
    print(f'{out} — script 위반 {len(script_lines)} · 명령 오류 {len(errors)} · {elapsed:.1f}초')


if __name__ == '__main__':
    main()
