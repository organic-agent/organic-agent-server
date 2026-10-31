#!/usr/bin/env python3
"""규칙 ID 동기화 점검. 저장소 루트에서 `python3 -I .claude/skills/rule-audit/scripts/check_ids.py`.

- 규칙 쪽: .claude/rules/*.md · .claude/spec/git-convention.md · CLAUDE.md 의 `<!-- ID [ID...] -->` 마커
- 검사 쪽: .claude/skills/rule-audit/reference/checks.md 표의 첫 열 ID
- 허용 목록: reference/allowlist-*.txt 첫 줄 `# rules: ID...`
하나라도 어긋나면 종료 코드 1.
"""
import glob, os, re, sys

ROOT = os.getcwd()
ID = r'[A-Z]+-[A-Za-z]?\d+'
MARK = re.compile(r'<!--\s*((?:' + ID + r'\s*)+)-->')
ROW = re.compile(r'^\|\s*(' + ID + r')\s*\|\s*([^|]*?)\s*\|', re.M)
RULES = sorted(glob.glob(os.path.join(ROOT, '.claude/rules/*.md'))) + [os.path.join(ROOT, '.claude/spec/git-convention.md'), os.path.join(ROOT, 'CLAUDE.md')]
CHECKS = os.path.join(ROOT, '.claude/skills/rule-audit/reference/checks.md')
ALLOW = sorted(glob.glob(os.path.join(ROOT, '.claude/skills/rule-audit/reference/allowlist-*.txt')))


def rd(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


marked, where = {}, {}
dups = []
for p in RULES:
    for m in MARK.finditer(rd(p)):
        for i in m.group(1).split():
            if i in marked:
                dups.append(f'{i}: {where[i]} 와 {os.path.relpath(p, ROOT)}')
            marked[i] = True
            where[i] = os.path.relpath(p, ROOT)
        # 같은 마커 안의 중복
        ids = m.group(1).split()
        for i in set(ids):
            if ids.count(i) > 1:
                dups.append(f'{i}: {os.path.relpath(p, ROOT)} 한 마커 안에서 두 번')

checks = {}
for m in ROW.finditer(rd(CHECKS)):
    checks[m.group(1)] = m.group(2)

no_check = sorted(set(marked) - set(checks))
no_rule = sorted(set(checks) - set(marked))
bad_allow = []
for p in ALLOW:
    first = rd(p).split('\n', 1)[0]
    m = re.match(r'#\s*rules:\s*((?:' + ID + r'\s*)+)', first)
    if not m:
        bad_allow.append(f'{os.path.relpath(p, ROOT)}: 첫 줄이 `# rules: ID…` 가 아니다')
        continue
    for i in m.group(1).split():
        if i not in checks:
            bad_allow.append(f'{os.path.relpath(p, ROOT)}: 모르는 ID {i}')

print(f'마커 ID {len(marked)} · 검사 ID {len(checks)} · 허용 목록 {len(ALLOW)}개')
print('(a) 규칙에 마커가 있는데 checks.md에 검사가 없는 ID:', no_check or '없음')
print('(b) checks.md에 검사가 있는데 규칙에 마커가 없는 ID:', no_rule or '없음')
print('(c) 허용 목록 머리글 문제:', bad_allow or '없음')
print('(d) 중복 마커:', dups or '없음')
sys.exit(1 if (no_check or no_rule or bad_allow or dups) else 0)
