#!/usr/bin/env python3
"""PreToolUse(Edit|Write|MultiEdit) — 이미 커밋된 Flyway 마이그레이션은 고치지 못하게 막는다(MG-04).

적용된 V 파일을 고치면 운영 DB의 체크섬과 어긋나 앱이 뜨지 않는다. 새 변경은 새 V 파일로.
git에 아직 없는 새 V 파일(작성 중인 것)은 통과시킨다.
"""
import json, os, re, subprocess, sys

MIGRATION = re.compile(r'(^|/)wes-api/src/main/resources/db/migration/V\d+__[^/]+\.sql$')


def main():
    data = json.load(sys.stdin)
    path = (data.get('tool_input') or {}).get('file_path') or ''
    root = os.environ.get('CLAUDE_PROJECT_DIR') or data.get('cwd') or os.getcwd()
    rel = os.path.relpath(os.path.abspath(path), root) if path else ''
    if not MIGRATION.search(rel):
        return 0
    tracked = subprocess.run(['git', 'ls-files', '--error-unmatch', rel], cwd=root, capture_output=True).returncode == 0
    if not tracked:
        return 0
    print(f'막음(MG-04): {rel} 은 이미 커밋된 마이그레이션이다. 적용된 V 파일을 고치면 운영 DB 체크섬과 어긋난다 — '
          f'바꿀 내용은 다음 번호의 새 V 파일로 만든다(.claude/rules/migration.md).', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
