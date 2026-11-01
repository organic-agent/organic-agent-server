#!/usr/bin/env python3
"""checks.md의 명령을 ID별로 돌려 결과를 남긴다. 저장소 루트에서:

    python3 -I .claude/skills/rule-audit/scripts/run_checks.py <출력 폴더>

- `script` 담당(G·S): 출력 줄이 곧 위반 → <출력>/script-violations.md
- G→J 담당: 출력 줄은 후보 → <출력>/candidates/<ID>.txt (담당 sonnet이 읽는다)
- 담당별 작업 목록 → <출력>/work-<담당>.md (ID · 출처 · 찾는 법 · 후보 파일)
- 허용 목록(reference/allowlist-*.txt)이 있으면 그 줄을 위반에서 빼고, 고쳐져서 더는 안 걸리는 줄을 따로 적는다.

비교 모드 — 이 브랜치가 새로 들여오는 위반만 본다(Stop hook · CI):

    python3 -I .claude/skills/rule-audit/scripts/run_checks.py --compare origin/develop          # 바꾼 파일만, H 규칙
    python3 -I .claude/skills/rule-audit/scripts/run_checks.py --compare origin/develop --full   # 저장소 전체, H+T 규칙

- 기준 = `<ref>`와 HEAD의 merge-base. 바꾼 파일 = 기준 대비 작업 트리 diff + 추적 안 된 새 파일.
- 기본(작은 나무): 바꾼 파일만 기준·지금 두 임시 폴더에 복사해 같은 명령을 cwd만 바꿔 돌린다.
- `--full`: 기준 커밋을 임시 worktree로 꺼내 저장소 전체를 두 번 돌린다(파일 여러 개를 함께 보는 T 규칙 포함).
- (규칙 ID, 경로)별 줄 수가 늘어난 것만 보고하고 종료 코드 1. 기존 위반은 양쪽에서 같이 세져 상쇄되므로 허용 목록은 쓰지 않는다.
- 대상은 script 담당 규칙 중 git 이력·브랜치 규칙(GIT-*, MG-04)과 권장(ID에 소문자)을 뺀 것.
- 명령이 돌지 못하면(rg 없음·시간 초과·그 밖의 실패) "위반 없음"이 아니라 종료 코드 2(검사 실패)로 끝난다.
"""
import glob, os, re, shutil, subprocess, sys, tempfile, time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

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


SKILL_DIR = '.claude/skills/rule-audit'
COMPARE_EXCLUDED = re.compile(r'^(GIT-\d+|MG-04)$|[a-z]')


def git(*args, cwd=None):
    return subprocess.run(['git', *args], cwd=cwd or ROOT, capture_output=True, text=True, check=True).stdout


def changed_files(base):
    """기준 대비 바뀐 파일(작업 트리 기준, 삭제 포함)과 추적 안 된 새 파일."""
    tracked = git('diff', '--name-only', base).split('\n')
    untracked = git('ls-files', '--others', '--exclude-standard').split('\n')
    return sorted({p for p in tracked + untracked if p})


# 작은 나무에는 일부 경로만 있어 "없는 경로" 류 오류가 정상으로 난다. 그 밖의 stderr는 검사가 실제로 돌지 못한 것이다.
BENIGN_STDERR = re.compile(r'No such file or directory|No files were searched|missing operand|Try .basename --help')


def run_all(cwd, ids, commands):
    """ID마다 명령을 cwd에서 돌려 ({ID: [출력 줄]}, [오류]). 명령이 돌지 못하면(도구 없음·시간 초과·그 밖의 실패) 오류로 돌려준다 —
    빈 출력으로 두면 "위반 없음"과 구별되지 않는다."""
    def one(rid):
        try:
            proc = subprocess.run(['bash', '-c', commands[rid]], cwd=cwd, capture_output=True, text=True, timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            return rid, [], f'{rid}: {TIMEOUT}초 초과'
        bad = [l for l in proc.stderr.splitlines() if l.strip() and not BENIGN_STDERR.search(l)]
        failed = 'command not found' in proc.stderr or (proc.returncode not in (0, 1) and bad)
        error = f'{rid}: 종료 코드 {proc.returncode} — {(bad or proc.stderr.splitlines() or [""])[0][:200]}' if failed else None
        return rid, [l for l in proc.stdout.splitlines() if l.strip()], error
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(one, ids))
    return {rid: lines for rid, lines, _ in results}, [e for _, _, e in results if e]


def path_of(line):
    """출력 줄에서 경로를 꺼낸다 — `경로:줄:내용` · `경로 4` · `MULTI 경로 2` 모두. 경로가 없으면 줄 앞부분."""
    for token in line.split():
        candidate = token.split(':', 1)[0]
        if '/' in candidate:
            return candidate
    return line.split(':', 1)[0].strip()


def body_of(line):
    """`경로:줄:내용`에서 내용만 — 줄 번호가 밀려도 같은 위반은 같은 내용으로 본다."""
    parts = line.split(':', 2)
    return parts[2].strip() if len(parts) == 3 and parts[1].isdigit() else line.strip()


def copy_tree(dest, files, read):
    """files의 각 경로를 read(path)가 준 바이트로 dest 아래에 만든다(None이면 건너뜀). 규칙 스크립트는 지금 것을 복사한다."""
    for p in files:
        data = read(p)
        if data is None:
            continue
        target = os.path.join(dest, p)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as f:
            f.write(data)
    shutil.copytree(os.path.join(ROOT, SKILL_DIR), os.path.join(dest, SKILL_DIR), dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('__pycache__'))


def read_at(base):
    def read(p):
        proc = subprocess.run(['git', 'show', f'{base}:{p}'], cwd=ROOT, capture_output=True)
        return proc.stdout if proc.returncode == 0 else None
    return read


def read_now(p):
    path = os.path.join(ROOT, p)
    if not os.path.isfile(path):
        return None
    with open(path, 'rb') as f:
        return f.read()


def compare(before, after, scope):
    """(ID, 경로)별 줄 수가 늘어난 것 → [(ID, 경로, 전, 후, [새 줄])]"""
    increases = []
    for rid in sorted(after):
        b = Counter(path_of(l) for l in before.get(rid, []))
        a = Counter(path_of(l) for l in after[rid])
        for path, n in sorted(a.items()):
            if scope is not None and path not in scope:
                continue
            if n <= b.get(path, 0):
                continue
            old = Counter(body_of(l) for l in before.get(rid, []) if path_of(l) == path)
            new = []
            for l in after[rid]:
                if path_of(l) != path:
                    continue
                key = body_of(l)
                if old[key] > 0:
                    old[key] -= 1
                else:
                    new.append(l)
            increases.append((rid, path, b.get(path, 0), n, new))
    return increases


def compare_main(args):
    if not args or args[0].startswith('-'):
        sys.exit('사용법: run_checks.py --compare <ref> [--full]')
    ref, full = args[0], '--full' in args[1:]
    rows, commands = parse(read(CHECKS))
    enforce = ('H', 'T') if full else ('H',)
    ids = [rid for rid, row in rows.items()
           if row['owner'] == 'script' and row['enforce'] in enforce and rid in commands and not COMPARE_EXCLUDED.search(rid)]
    started = time.time()
    base = git('merge-base', 'HEAD', ref).strip()
    files = changed_files(base)
    if not files:
        print(f'바꾼 파일 없음 (기준 {base[:8]})')
        return 0

    tmp = tempfile.mkdtemp(prefix='rule-compare-')
    try:
        if full:
            before_root = os.path.join(tmp, 'before')
            git('worktree', 'add', '--detach', '--quiet', before_root, base)
            try:
                shutil.copytree(os.path.join(ROOT, SKILL_DIR), os.path.join(before_root, SKILL_DIR), dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns('__pycache__'))
                before, errors_before = run_all(before_root, ids, commands)
            finally:
                git('worktree', 'remove', '--force', before_root)
            after, errors_after = run_all(ROOT, ids, commands)
            scope = None
        else:
            before_root, after_root = os.path.join(tmp, 'before'), os.path.join(tmp, 'after')
            copy_tree(before_root, files, read_at(base))
            copy_tree(after_root, files, read_now)
            before, errors_before = run_all(before_root, ids, commands)
            after, errors_after = run_all(after_root, ids, commands)
            scope = set(files)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    errors = sorted(set(errors_before + errors_after))
    if errors:
        print(f'검사 실패 — 명령 {len(errors)}개가 돌지 못해 위반 여부를 알 수 없다(기준 {base[:8]}). ripgrep(rg)이 설치돼 있는지 먼저 본다.')
        for e in errors[:10]:
            print(f'   {e}')
        return 2
    allowed = path_allowlist()
    excused = [i for i in compare(before, after, scope) if i[1] in allowed.get(i[0], set())]
    increases = [i for i in compare(before, after, scope) if i not in excused]
    elapsed = time.time() - started
    mode = '저장소 전체' if full else f'바꾼 파일 {len(files)}개'
    note = f' · 허용 목록으로 넘어간 곳 {len(excused)}' if excused else ''
    if not increases:
        print(f'늘어난 위반 없음 — 기준 {base[:8]} · {mode} · 규칙 {len(ids)}개 · {elapsed:.1f}초{note}')
        return 0
    print(f'늘어난 위반 {len(increases)}곳 — 기준 {base[:8]} · {mode} · 규칙 {len(ids)}개 · {elapsed:.1f}초{note}\n')
    for rid, path, b, a, new in increases:
        where, sentence = rule_sentence(rid)
        print(f'{rid}  {path}  {b} → {a}')
        print(f'   규칙 {where}: {sentence}')
        for l in new[:5]:
            print(f'   + {l}')
        print()
    print(COMPARE_FOOTER.format(ref=ref, full=' --full' if full else ''))
    return 1


COMPARE_FOOTER = """고치는 법
  - 위 `+` 줄이 이 브랜치가 새로 만든 위반이다. 규칙 문장을 읽고 고친다(원래 있던 위반은 세지 않는다).
  - 로컬에서 다시 확인: python3 .claude/skills/rule-audit/scripts/run_checks.py --compare {ref}{full}   (ripgrep 필요: brew install ripgrep)
  - 규칙이 이 경우를 허용해야 한다고 판단되면: .claude/skills/rule-audit/reference/allowlist-*.txt 에 `경로` 한 줄을 넣고
    바로 위에 `# 까닭` 주석을 단다(첫 줄 `# rules: ID …`가 그 규칙을 덮는 파일에, 없으면 새 파일). 리뷰에서 그 줄을 본다."""


def path_allowlist():
    """비교 모드용 — 허용 목록 중 `경로`만 적힌 줄(줄 번호 없는 것). 줄 번호 항목은 편집으로 밀리므로 비교에서는 쓰지 않는다."""
    allowed = {}
    for _, ids, entries in allowlists():
        paths = {e for e in entries if ':' not in e}
        for i in ids:
            allowed.setdefault(i, set()).update(paths)
    return allowed


def rule_sentence(rid):
    """규칙 마커 `<!-- … ID … -->`가 붙은 줄 → ('파일:줄', 문장 앞부분)."""
    marker = re.compile(r'<!--[^>]*(?<![\w-])' + re.escape(rid) + r'(?![\w-])[^>]*-->')
    sources = sorted(glob.glob(os.path.join(ROOT, '.claude/rules/*.md'))) + \
        sorted(glob.glob(os.path.join(ROOT, '.claude/spec/*.md'))) + [os.path.join(ROOT, 'CLAUDE.md')]
    for p in sources:
        if not os.path.isfile(p):
            continue
        for n, line in enumerate(read(p).splitlines(), 1):
            if marker.search(line):
                text = re.sub(r'<!--.*?-->', '', line).strip(' -*')
                text = re.sub(r'\*\*', '', text)
                return f'{os.path.relpath(p, ROOT)}:{n}', (text[:140] + '…') if len(text) > 140 else text
    return '(규칙 파일에서 못 찾음)', ''


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == '--compare':
        sys.exit(compare_main(sys.argv[2:]))
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
        if owner not in ('script', 'test'):
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
