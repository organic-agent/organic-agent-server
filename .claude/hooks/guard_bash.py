#!/usr/bin/env python3
"""PreToolUse(Bash) — 되돌릴 수 없는 명령을 Claude가 실행하지 못하게 막는다. 필요하면 사용자가 `! <명령>`으로 직접 실행한다.

막는 것(계획 D14 A):
  ① 운영 데이터 쓰기 — `remote`(= 운영 15432·/wes/prod) 대상 데이터 스크립트, 읽기 전용 옵션 없는 운영 15432 `psql`
  ② 운영 배포 — main으로 가는 `git push`, `gh workflow run cd-prod`, base가 main인 `gh pr merge`, `ready-to-merge` 라벨,
     배포 스크립트 직접 실행
  ③ 커밋된 마이그레이션을 Bash로 고치기·지우기 — `sed -i`·`rm`·`mv`·리다이렉트 등

명령 문자열만 보므로 안전벨트다 — 새 스크립트가 안에서 운영에 쓰는 것까지는 모른다. 우회 스위치는 두지 않는다.
"""
import json, os, re, shlex, subprocess, sys

ROOT = os.environ.get('CLAUDE_PROJECT_DIR') or os.getcwd()
READ_ONLY = 'default_transaction_read_only=on'
REMOTE_DATA_SCRIPTS = {'reset-test-data.sh', 'delete-accounts.sh', 'clone-gallery-photos.sh'}
DEPLOY_SCRIPTS = {'rolling-deploy.sh', 'deploy-public-ssm.sh', 'deploy-admin-api-ssm.sh'}
WRAPPERS = {'sudo', 'env', 'time', 'command', 'exec', 'nohup'}
SHELLS = {'bash', 'sh', 'zsh', 'source', '.'}
MIGRATION_PATH = re.compile(r'''[^\s'"<>|;&]*db/migration/[^\s'"<>|;&]*''')
SEGMENT_SPLIT = re.compile(r'\|\||&&|[;\n|]|(?<![<>0-9])&(?!>)')


def tokens_of(segment):
    try:
        return shlex.split(segment, comments=True)
    except ValueError:
        return segment.split()


def effective(tokens):
    """환경 변수 대입·래퍼를 걷어 낸 (실행 프로그램 이름, 나머지 토큰). 셸로 스크립트를 부르면 그 스크립트가 프로그램이다."""
    i = 0
    while i < len(tokens) and (re.match(r'^\w+=', tokens[i]) or tokens[i] in WRAPPERS):
        i += 1
    rest = tokens[i:]
    if not rest:
        return '', []
    prog = os.path.basename(rest[0])
    if prog in SHELLS and len(rest) > 1 and rest[1] != '-c':
        args = [t for t in rest[1:] if not t.startswith('-')]
        if args:
            return os.path.basename(args[0]), args[1:]
    return prog, rest[1:]


def tracked(path):
    if any(c in path for c in '*?['):
        return True
    return subprocess.run(['git', 'ls-files', '--error-unmatch', path], cwd=ROOT, capture_output=True).returncode == 0


def current_branch():
    return subprocess.run(['git', 'branch', '--show-current'], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def pr_base(target):
    cmd = ['gh', 'pr', 'view', *([target] if target else []), '--json', 'baseRefName', '-q', '.baseRefName']
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=20)
    return proc.stdout.strip() if proc.returncode == 0 else None


def check_git(args):
    i = 0
    while i < len(args) and args[i].startswith('-'):
        i += 2 if args[i] in ('-C', '-c') else 1
    if i >= len(args):
        return None
    sub, rest = args[i], args[i + 1:]
    if sub in ('rm', 'mv') and any(tracked(p) for p in MIGRATION_PATH.findall(' '.join(rest))):
        return '커밋된 마이그레이션 파일을 git으로 지우거나 옮긴다(MG-04)'
    if sub != 'push':
        return None
    if any(o in rest for o in ('--all', '--mirror')):
        return 'main을 포함할 수 있는 git push --all/--mirror(main push = 운영 배포)'
    refs = [a for a in rest if not a.startswith('-')]
    for ref in refs[1:]:
        dest = ref.split(':', 1)[-1].lstrip('+')
        if dest in ('main', 'refs/heads/main'):
            return f'main으로 push한다({ref}) — main push가 곧 운영 배포(cd-prod.yml)'
    if len(refs) <= 1 and current_branch() == 'main':
        return '지금 브랜치가 main이라 이 push가 main으로 간다 — 운영 배포'
    return None


def check_gh(args, segment):
    if 'ready-to-merge' in segment and ('--add-label' in args or (args[:1] == ['api'])):
        return 'ready-to-merge 라벨은 ff-merge로 main에 push된다 — 운영 배포'
    if args[:2] == ['workflow', 'run'] and any('cd-prod' in a for a in args[2:]):
        return '운영 배포 워크플로(cd-prod) 실행'
    if args[:2] == ['pr', 'merge']:
        target = next((a for a in args[2:] if not a.startswith('-')), None)
        try:
            base = pr_base(target)
        except subprocess.TimeoutExpired:
            base = None
        if base is None:
            return 'PR의 base 브랜치를 확인하지 못했다 — main일 수 있어 막는다'
        if base == 'main':
            return 'base가 main인 PR 머지 — 운영 배포'
    return None


def check_migration(prog, args, segment):
    paths = MIGRATION_PATH.findall(segment)
    if not paths:
        return None
    reason = '커밋된 마이그레이션 파일을 Bash로 고치거나 지운다(MG-04) — 바꿀 내용은 새 V 파일로'
    redirect_targets = re.findall(r'''>>?\s*([^\s'"<>|;&]*db/migration/[^\s'"<>|;&]*)''', segment)
    if any(tracked(p) for p in redirect_targets):
        return reason
    if prog in ('rm', 'mv', 'truncate', 'dd', 'unlink') and any(tracked(p) for p in paths):
        return reason
    if prog in ('sed', 'gsed', 'perl') and any(a == '-i' or a.startswith('-i') for a in args) and any(tracked(p) for p in paths):
        return reason
    if prog == 'tee' and any(tracked(p) for p in paths):
        return reason
    if prog in ('cp', 'install'):
        dests = [a for a in args if not a.startswith('-')]
        if dests and MIGRATION_PATH.fullmatch(dests[-1]) and tracked(dests[-1]):
            return reason
    return None


def check_segment(segment, whole):
    tokens = tokens_of(segment)
    prog, args = effective(tokens)
    if not prog:
        return None
    if prog in SHELLS and args[:1] == ['-c'] and len(args) > 1:
        return check_command(args[1])
    if prog in REMOTE_DATA_SCRIPTS and 'remote' in args:
        return f'{prog} remote 는 운영 DB(15432·/wes/prod)를 바꾼다'
    if prog in DEPLOY_SCRIPTS:
        return f'{prog} 는 배포 스크립트다 — 배포는 CD(cd-dev/cd-prod)가 한다'
    if (prog == 'psql' or (prog == 'docker' and 'psql' in tokens)) and '15432' in segment and READ_ONLY not in whole:
        return (f"운영 DB(15432)에 읽기 전용 옵션 없이 psql — 조회면 PGOPTIONS='-c {READ_ONLY}'를 붙인다"
                f"(docker면 -e PGOPTIONS='-c {READ_ONLY}')")
    if prog == 'git':
        return check_git(args)
    if prog == 'gh':
        return check_gh(args, segment)
    return check_migration(prog, args, segment)


def check_command(command):
    for segment in SEGMENT_SPLIT.split(command):
        if segment.strip():
            reason = check_segment(segment, command)
            if reason:
                return reason
    return None


def main():
    data = json.load(sys.stdin)
    command = (data.get('tool_input') or {}).get('command') or ''
    reason = check_command(command)
    if not reason:
        return 0
    print(f'막음: {reason}.\n되돌릴 수 없는 명령이라 Claude는 실행하지 않는다. 필요하면 사용자에게 직접 실행을 부탁한다: ! {command.strip()}',
          file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
