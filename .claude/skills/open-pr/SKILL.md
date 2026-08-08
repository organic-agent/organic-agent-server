---
name: open-pr
description: |
  PR을 생성한다. pull_request_template.md 규격에 맞춰 본문을 작성하고 gh pr create를 실행한다.
  Trigger: "PR 날려줘", "PR 만들어줘", "PR 생성해줘", "PR 올려줘"
  Do NOT use for: 커밋·푸시(→ commit-push), 이슈·브랜치 생성(→ open-issue), 코드 리뷰
  Boundary: PR 생성까지만 수행한다. 머지, 리뷰 요청, 라벨 설정은 범위 밖이다.
allowed-tools: Bash(git *), Bash(gh *), Read, Write
model: sonnet
effort: xhigh
---

# PR 생성

대상 브랜치: $ARGUMENTS (비어있으면 `main`)

이 저장소는 `dev` 없이 작업 브랜치가 `main`에서 분기해 `main`으로 병합된다
(`.claude/spec/git-convention.md`). 그래서 base 기본값은 `main`이다.

가장 먼저 `.claude/spec/change-evidence.md`를 Read한다. 기존 코드나 동작을 수정한 PR은 연결 이슈의
작업 분류에 맞는 근거, 최소 범위와 실제 검증 결과를 확인할 수 없으면 생성하지 않는다.

## Phase 1: 현재 브랜치 및 변경 사항 파악

1. `.claude/spec/change-evidence.md`를 Read하고 중단 조건을 확인하라.
2. `git branch --show-current`로 현재 브랜치명을 확인하라. `main`이면 PR을 열 수 없으니 중단하라.
3. 브랜치명에서 이슈 번호를 추출하라 (형식: `{type}/{이슈번호}-{slug}`)
   - 예: `feat/123-bookmark` → 이슈 번호 `123`
   - 이슈 번호가 없으면 사용자에게 물어보라.
4. base 브랜치를 최신화하고(`git fetch origin {base}`), 이 브랜치의 커밋과 변경 파일을 확인하라:
   ```bash
   git log origin/{base}..HEAD --oneline
   git diff origin/{base}...HEAD --stat
   ```
   - 커밋이 하나도 없으면 PR을 열 게 없다. 알리고 중단하라.
5. diff를 기존 동작 변경, 동작 보존 수정, 기존 계약을 바꾸지 않는 추가 기능, 순수 신규 산출물로 분류한다.
6. `gh issue view {이슈번호} --json url,title,body,state`로 이슈의 변경 근거와 비목표를 직접 확인하라.
   기존 동작 변경이면 전체 근거를, 동작 보존 수정이나 추가 기능이면 `.claude/spec/change-evidence.md`의
   경량 근거를 확인하고 diff가 기록된 최소 범위 안에 있는지 확인한다. 순수 추가 기능에 기존 결함의
   재현 절차를 요구하지 않는다. 필요한 근거가 없거나 placeholder뿐이거나 scope가 달라졌으면 중단하라.
7. 현재 작업에서 실제로 실행한 검증 명령·입력과 결과를 확인하라. 미실행 검증은 사유, 대체 확인,
   남는 위험이 있어야 한다. 확인할 수 없으면 PR 생성을 중단한다.
8. 이미 열린 PR이 있는지 확인하라 (`gh pr view --json url,state 2>/dev/null`).
   있으면 URL을 알리고 중단하라 — push된 커밋은 기존 PR에 이미 반영된다.

> 다음 Phase 조건: 이슈 번호와 변경 사항이 파악되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 2: PR 제목 및 본문 작성

1. `.github/pull_request_template.md`를 Read로 읽어 섹션 구조(`### 1. 연관 이슈`, `### 2. 구현 사항`,
   `### 3. 변경 근거`, `### 4. 검증`)와
   구분선(`---`)을 그대로 따르라. 템플릿의 안내 문구(`❗️...`)는 지우고 실제 내용으로 채운다.
2. `.claude/spec/git-convention.md`를 Read로 읽어 PR 제목의 커밋 메시지 형식을 확인하라.
3. 본문은 템플릿 섹션에 맞춰 컴팩트하게 작성하라:
   - `1. 연관 이슈`: `- close #{이슈번호}`
   - `2. 구현 사항`: 이 브랜치의 변경을 추가/수정한 것 위주로 항목화하라.
     "무엇을 어떻게 추가/수정했는지"만 명확히 적고, 불필요한 서술은 늘리지 마라.
     - 항목 예: `- 추가: {무엇을 — 어디에}`, `- 수정: {무엇을 — 어떻게}`
     - 변경이 여러 갈래면 `**구현 사항 1**`, `**구현 사항 2**`로 분리
     - 특별한 로직·설계를 채택한 부분만 근거를 한두 줄 덧붙여라 (그 외 근거 서술은 생략)
     - 커밋 메시지를 그대로 복사하지 마라 — Phase 1의 diff를 이해한 뒤 요약하라
   - `3. 변경 근거`: 기존 동작 변경은 원 계약·결정 출처, 재현 절차, Actual, Expected, 영향 범위,
     검토한 대안, 최소 변경 및 이슈의 비목표 준수 근거를 구체적으로 적는다. 동작 보존 수정이나
     기존 계약을 바꾸지 않는 추가 기능은 분류에 맞는 경량 근거를 적고 결함 재현을 지어내지 않는다.
     작성자나 `git blame`을 정당화 근거로 쓰지 않는다.
   - `4. 검증`: 실제 실행한 명령·입력과 결과를 적는다. 미실행 항목은 사유, 대체 확인, 남는 위험을 적는다.
     `테스트 통과`처럼 재현할 수 없는 요약만 쓰지 않는다.
4. PR 제목은 git-convention.md의 커밋 메시지 형식을 따른다: `{type}: {설명}(#{이슈번호})`
5. 완성한 본문을 스크래치 파일에 Write하라. `gh`에는 `--body-file`로 넘긴다 —
   한국어·백틱·따옴표가 섞인 본문을 `--body`에 인라인으로 넣으면 셸이 깨먹는다.

> 다음 Phase 조건: 제목과 본문 파일이 준비되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 3: PR 생성

1. 대상 브랜치를 결정하라: $ARGUMENTS가 있으면 해당 브랜치, 없으면 `main`.
2. 현재 브랜치가 원격에 push되어 있는지, 로컬에 안 올라간 커밋이 없는지 확인하라:
   ```bash
   git rev-parse HEAD
   git rev-parse origin/{현재브랜치} 2>/dev/null
   ```
   - 원격 브랜치가 없거나 해시가 다르면 push되지 않은 커밋이 있는 것이다.
     사용자에게 알리고 중단하라 (commit-push 스킬로 먼저 올려야 한다).
3. 다음 명령으로 PR을 생성하라:
   ```bash
   gh pr create --title "{제목}" --body-file {본문파일} --base {대상 브랜치}
   ```
4. 생성된 PR URL을 제목·base와 함께 사용자에게 보고하라.

> Skip 조건: 없음 (필수 Phase)
