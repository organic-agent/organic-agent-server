#!/usr/bin/env python3
"""Stop — Claude가 답을 끝내려 할 때, 이 브랜치가 새로 들여오는 규칙 위반이 있는지 본다(계획 D12·D13).

- 기준 = origin/develop과의 merge-base. 바꾼 파일만 두 임시 폴더(기준·지금)에 복사해 H 규칙 수를 비교한다
  (`run_checks.py --compare`, 같은 checks.md 명령).
- 늘어난 위반이 있으면 exit 2 → Claude가 끝내지 못하고 고친다.
- 같은 내용으로 이미 한 번 알렸거나(stop_hook_active · 같은 변경 지문) 오탐이라 넘어간 경우엔 막지 않고 사용자에게 보여만 준다.
- 변경 지문(기준 대비 diff + 새 파일 내용)이 직전 통과 때와 같으면 검사 없이 통과한다.
"""
import hashlib, json, os, subprocess, sys

ROOT = os.environ.get('CLAUDE_PROJECT_DIR') or os.getcwd()
BASE_REF = 'origin/develop'
RUN_CHECKS = os.path.join(ROOT, '.claude/skills/rule-audit/scripts/run_checks.py')


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True).stdout


def fingerprint():
    base = git('merge-base', 'HEAD', BASE_REF).strip()
    if not base:
        return None
    h = hashlib.sha256(base)
    h.update(git('diff', base.decode()))
    for p in sorted(git('ls-files', '--others', '--exclude-standard').decode().split('\n')):
        path = os.path.join(ROOT, p)
        if p and os.path.isfile(path):
            h.update(p.encode())
            with open(path, 'rb') as f:
                h.update(f.read())
    return h.hexdigest()


def state_path():
    git_dir = git('rev-parse', '--absolute-git-dir').decode().strip()
    return os.path.join(git_dir, 'rule-audit-stop.json')


def load_state():
    try:
        with open(state_path(), encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(state):
    try:
        with open(state_path(), 'w', encoding='utf-8') as f:
            json.dump(state, f)
    except OSError:
        pass


def main():
    data = json.load(sys.stdin)
    key = fingerprint()
    if key is None:
        return 0
    state = load_state()
    if state.get('key') == key and state.get('result') == 'pass':
        return 0

    proc = subprocess.run([sys.executable, '-I', RUN_CHECKS, '--compare', BASE_REF], cwd=ROOT, capture_output=True, text=True,
                          timeout=50)
    if proc.returncode == 0:
        save_state({'key': key, 'result': 'pass'})
        return 0
    if proc.returncode != 1:
        print(json.dumps({'systemMessage': f'규칙 비교 검사가 실행되지 않았다(종료 코드 {proc.returncode}): {proc.stderr.strip()[:300]}'}))
        return 0

    report = proc.stdout.strip()
    already = data.get('stop_hook_active') or (state.get('key') == key and state.get('result') == 'reported')
    save_state({'key': key, 'result': 'reported'})
    if already:
        print(json.dumps({'systemMessage': '규칙 검사 — 아래 위반이 남은 채 끝났다(오탐이면 무시):\n' + report}, ensure_ascii=False))
        return 0
    print('이 브랜치가 새로 들여오는 규칙 위반이 있다. 고치고 끝내라 — 규칙이 이 경우를 허용한다고 판단되면 '
          '까닭을 사용자에게 말하고 넘어가도 된다(다시 막지 않는다).\n\n' + report, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
