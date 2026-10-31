---
name: rule-audit
description: |
  `.claude/rules`의 필수 규칙으로 서버 코드 전체를 검토한다. 기계로 찾을 수 있는 규칙은 스크립트가, 읽고 판단할 규칙은 담당 그룹별 서브 에이전트(sonnet 5개)가 맡고, 메인은 합쳐서 보고서를 쓴다.
  Trigger: "/rule-audit", "규칙 검토 돌려줘", "규칙대로 짜였는지 전체 점검"
  Do NOT use for: 변경분 리뷰(→ code-review), 한 도메인 리팩터링 진단(→ refactor-domain), 테스트 보강(→ qa-domain)
  Boundary: 코드를 고치지 않는다. 보고서만 쓴다. 위반을 고치는 일은 별도 이슈로.
allowed-tools: Read, Grep, Glob, Write, Bash, Agent
---

# 규칙 검토

규칙 문장은 `.claude/rules/*.md`·`.claude/spec/git-convention.md`·`CLAUDE.md`에만 있고 문장 끝에 `<!-- ID -->`가 붙어 있다.
이 스킬은 문장을 복사하지 않는다 — `reference/checks.md`가 ID별 **찾는 법**과 **담당**만 안다.

## Phase 0: 준비

1. 저장소 루트에서 ID 짝을 확인한다. 실패하면 멈추고 어긋난 ID를 보고한다(규칙과 검사가 어긋난 채로 돌리지 않는다):
   ```bash
   python3 -I .claude/skills/rule-audit/scripts/check_ids.py
   ```
2. 출력 폴더를 정한다: `docs/improvements/<오늘 YYYY-MM-DD>/rule-audit-<HHMM>/` (`docs/`는 gitignore). 시작 시각을 적어 둔다.

## Phase 1: 스크립트 (G·S 규칙, 에이전트 없음)

```bash
python3 -I .claude/skills/rule-audit/scripts/run_checks.py <출력 폴더>
```

- `script-violations.md`: script 담당 규칙의 위반 — 출력 줄이 곧 위반이다.
- `candidates/<ID>.txt`: G→J 규칙의 후보.
- `work-sonnet-<a~e>.md`: 그룹별 작업 목록.
- `run-summary.md`: 명령 오류 · 허용 목록 · ID별 줄 수. **명령 오류가 있으면 그 ID를 보고서에 "검사 실패"로 적는다**(0건으로 세지 않는다).

## Phase 2: 판단형 규칙 (sonnet 5개, 한 메시지에서 병렬)

작업 목록 파일이 있는 그룹마다 `rule-auditor` 에이전트를 하나씩 띄운다. **프롬프트는 아래 틀 그대로** — 어디를 특히 보라는 말, 예상 위반, 이전 결과를 덧붙이지 않는다(검토 결과가 메인의 짐작을 따라가지 않게).

```
담당 그룹: sonnet-<x>
작업 목록: <출력 폴더>/work-sonnet-<x>.md
후보 파일 폴더: <출력 폴더>/candidates/
작업 목록의 규칙을 정의대로 검토하고 정해진 형식으로 보고하라.
```

`subagent_type: "rule-auditor"`(모델 sonnet, 도구 Read·Grep·Glob·Bash로 고정 — `.claude/agents/rule-auditor.md`).

## Phase 3: 합치기 (메인)

1. 각 그룹의 응답을 `<출력 폴더>/sonnet-<x>.md`로 그대로 저장한다.
2. `<출력 폴더>/report.md`를 쓴다:
   - 머리말: 실행 시각 · 걸린 시간(Phase 0 시작 → 지금) · 브랜치·커밋(`git rev-parse --short HEAD`) · 에이전트별 토큰(알 수 있으면)
   - 영역별 표: | ID | 담당 | 위반 수 | 대표 위치 2~3 |
   - 판단이 애매했던 것: 그룹 보고의 "애매" 항목을 모아 규칙 문장을 고칠 후보로
   - 검사 실패한 ID
   - 허용 목록: 고쳐져서 지워도 되는 줄(`run-summary.md`), 허용 목록 덕에 0인 규칙
3. 메인은 판단을 다시 하지 않는다 — 그룹 보고를 그대로 옮긴다. 위반 수는 각 `### <ID>` 아래 `- <경로>:<줄>`로 시작하는 줄을 센다.
   범위(`:53-56`)·쉼표 묶음 등 형식을 어긴 줄은 "형식 불완전"으로 따로 세고 어림하지 않는다("약 n"으로 쓰지 않는다).

## Phase 4: 보고

사용자에게 아래 순서로 **한 번씩만** 쓴다(같은 단락을 반복하지 않는다):

1. 한 줄: 출력 폴더 · 걸린 시간 · 브랜치·커밋
2. 영역별 표: | 영역 | 스크립트 | 판단형 | 형식 불완전 |
3. 위반이 많은 규칙 상위 5
4. 판단이 애매했던 것 (상위 5, 전체는 report.md)
5. 검사 실패·허용 목록 변화 (없으면 "없음" 한 줄)
6. 고칠 순서 제안 (결정은 사용자)

## 운영 메모

- 허용 목록(`reference/allowlist-*.txt`, 첫 줄 `# rules: ID…`)은 정리 전 기존 위반을 묶어 새 위반만 보이게 한다. **줄어들기만 해야 한다** — 고쳐진 줄은 지우고, 새 규칙을 도입할 때만 새 목록을 만든다.
- 규칙을 더하거나 지우면 규칙 파일의 `<!-- ID -->`와 `checks.md`의 행·명령을 같은 커밋에서 맞춘다. `check_ids.py`가 어긋남을 잡는다.
