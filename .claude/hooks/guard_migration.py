#!/usr/bin/env python3
"""PreToolUse(Edit|Write|MultiEdit) — develop에 이미 들어간 Flyway 마이그레이션은 고치지 못하게 막는다(MG-04).

develop에 들어간 V 파일은 dev(나아가 운영) DB에 적용됐다. 고치면 체크섬이 어긋나 앱이 뜨지 않는다 — 새 변경은 새 V 파일로.
기준은 `origin/develop`과 갈라진 지점(merge-base)이다. 이 브랜치에서 새로 만든 V 파일은 커밋한 뒤에도 고칠 수 있다.
"""
import json, os, re, subprocess, sys

MIGRATION = re.compile(r'(^|/)wes-api/src/main/resources/db/migration/V\d+__[^/]+\.sql$')
BASE_REF = 'origin/develop'


def main():
    data = json.load(sys.stdin)
    path = (data.get('tool_input') or {}).get('file_path') or ''
    root = os.environ.get('CLAUDE_PROJECT_DIR') or data.get('cwd') or os.getcwd()
    rel = os.path.relpath(os.path.abspath(path), root) if path else ''
    if not MIGRATION.search(rel):
        return 0
    base = subprocess.run(['git', 'merge-base', 'HEAD', BASE_REF], cwd=root, capture_output=True, text=True).stdout.strip() or 'HEAD'
    applied = subprocess.run(['git', 'cat-file', '-e', f'{base}:{rel}'], cwd=root, capture_output=True).returncode == 0
    if not applied:
        return 0
    print(f'막음(MG-04): {rel} 은 develop에 이미 들어간(적용된) 마이그레이션이다. 고치면 DB 체크섬과 어긋난다 — '
          f'바꿀 내용은 다음 번호의 새 V 파일로 만든다(.claude/rules/migration.md).', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
