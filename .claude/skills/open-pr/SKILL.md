---
name: open-pr
description: |
  PR을 생성한다. pull_request_template.md 규격에 맞춰 본문을 작성하고 gh pr create로 만들면서 작성자 assignee와 타입 라벨을 단다.
  Trigger: "PR 날려줘", "PR 만들어줘", "PR 생성해줘", "PR 올려줘"
  Do NOT use for: 커밋·푸시(→ commit-push), 이슈·브랜치 생성(→ open-issue), 코드 리뷰
  Boundary: PR 생성과 assignee(작성자 본인)·타입 라벨 달기까지 수행한다. 머지, 리뷰 요청, `ready-to-merge` 라벨은 범위 밖이다.
allowed-tools: Bash(git *), Bash(gh *), Read, Write
model: sonnet
effort: xhigh
---

# PR 생성

대상 브랜치: $ARGUMENTS (비어있으면 `develop`, hotfix 브랜치면 `main`)

이 저장소는 작업 브랜치가 `develop`에서 분기해 `develop`으로 병합되고(dev 서버 배포), 운영 반영은 develop → main FF다
(`.claude/spec/git-convention.md`). 그래서 base 기본값은 `develop`이다. hotfix만 `main`으로 간다.

## Phase 1: 현재 브랜치 및 변경 사항 파악

1. `git branch --show-current`로 현재 브랜치명을 확인하라. `main`·`develop`이면 PR을 열 수 없으니 중단하라.
2. 브랜치명에서 이슈 번호를 추출하라 (형식: `{type}/{이슈번호}-{slug}`)
   - 예: `feat/123-bookmark` → 이슈 번호 `123`
   - 이슈 번호가 없으면 사용자에게 물어보라.
3. base 브랜치를 최신화하고(`git fetch origin {base}`), 이 브랜치의 커밋과 변경 파일을 확인하라:
   ```bash
   git log origin/{base}..HEAD --oneline
   git diff origin/{base}...HEAD --stat
   ```
   - 커밋이 하나도 없으면 PR을 열 게 없다. 알리고 중단하라.
4. 이미 열린 PR이 있는지 확인하라 (`gh pr view --json url,state 2>/dev/null`).
   있으면 URL을 알리고 중단하라 — push된 커밋은 기존 PR에 이미 반영된다.
5. CI `rules` job과 같은 규칙 비교 검사를 먼저 돌려라 — Stop hook은 바꾼 파일만 보므로 파일 여러 개를 함께 보는 규칙(T)은 여기서 처음 드러난다:
   ```bash
   python3 -I .claude/skills/rule-audit/scripts/run_checks.py --compare origin/{base} --full
   ```
   - "늘어난 위반 없음"이면 계속한다.
   - 늘어난 위반이 있으면 출력 그대로 사용자에게 보여 주고 멈춰라. 고치거나, 규칙이 허용하는 경우면 허용 목록에 경로 + 까닭을
     넣을지 사용자가 정한다. 그대로 PR을 열면 CI가 빨갛게 된다.

> 다음 Phase 조건: 이슈 번호와 변경 사항이 파악되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 2: PR 제목 및 본문 작성

1. `.github/pull_request_template.md`를 Read로 읽어 섹션 구조(`## 1. 연관 이슈`, `## 2. 구현 사항`)를
   그대로 따르라. 템플릿의 안내 문구(`❗️...`)는 지우고 실제 내용으로 채운다.
2. `.claude/spec/git-convention.md`를 Read로 읽어 PR 제목의 커밋 메시지 형식을 확인하라.
3. `.claude/spec/issue-pr-writing.md`를 Read로 읽어라. **본문은 이 가이드대로 쓴다** — 템플릿은 섹션 이름을,
   가이드는 섹션 안을 채우는 방법(한 줄 요약, 왜/무엇을/확인 세 칸, 전→후 표, 사람이 말하듯 쓰는 말투)을 정한다.
   - **말투**: 칸 안의 설명은 `~했습니다`·`~입니다`로 끝나는 완결된 문장으로, 동료에게 설명하듯 쓴다. 명사형 끝맺음(`~추가`·`~함`)과
     화살표·가운뎃점으로 이은 메모는 쓰지 않는다. 표는 전→후·측정값처럼 나란히 비교할 때만 쓴다(가이드 "말투" 절)
   - 맨 위: `>` 한 줄 요약. 머지·배포 순서처럼 놓치면 사고가 나는 조건이 있을 때만 `> [!WARNING]` 하나
   - `1. 연관 이슈`: `- close #{이슈번호}`
   - `2. 구현 사항`: `### 왜` / `### 무엇을 바꿨나`(이름·동작은 `전 | 후` 표) / `### 어떻게 확인했나` / `### 판단` 순서.
     `### 판단`은 W²HO의 Opinion(뜻: `.claude/skills/design-plan/reference/glossary.md`) — 고른 것과 버린 대안, 감수한 트레이드오프, 남긴 것·다음 할 일 중 1~3줄.
     인자나 대화에 `design-plan` 계획 문서 경로가 있으면 그 문서의 결정·트레이드오프·"판단" 칸에서 가져온다. 간단한 수정이면 한 줄로 충분하다
     칸 제목은 `###` 소제목이다 — 굵은 글씨와 `####`는 GitHub에서 본문과 크기가 같아 칸이 눈에 안 들어온다
     - 커밋 목록을 옮기지 마라. Phase 1의 diff를 이해한 뒤 묶음 3~5개로 요약하라
     - 동작이 그대로인 리팩터링이면 "동작은 그대로"를 한 줄로 쓰고, 지키는 테스트를 확인 칸에 쓴다
     - 흐름(호출 순서·상태 전이)이 바뀐 경우에만 작은 Mermaid 그림 하나
   - 본문이 화면 한 장(약 40줄)을 넘으면 세부 목록은 `<details>`로 접는다
4. PR 제목은 git-convention.md의 커밋 메시지 형식을 따른다: `{type}: {설명}(#{이슈번호})`.
   설명은 **결과를 말하는 한국어 한 구절**로 쓴다 (좋음: `사진 배정 이름을 세부 폴더 배정으로 변경`,
   나쁨: `DetailFolderAssignment rename (D9)`).
5. 가이드 끝의 **자가 점검** 항목을 하나씩 확인하고, 걸리는 것을 고쳐라.
6. 완성한 본문을 스크래치 파일에 Write하라. `gh`에는 `--body-file`로 넘긴다 —
   한국어·백틱·따옴표가 섞인 본문을 `--body`에 인라인으로 넣으면 셸이 깨먹는다.
7. 제목과 본문을 사용자에게 보여 주고 확인을 받아라. 고칠 곳을 말하면 반영한 뒤 다시 보여 준다.

> 다음 Phase 조건: 제목과 본문 파일이 준비되고, 사용자가 초안을 확인했을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 3: PR 생성

1. 대상 브랜치를 결정하라: $ARGUMENTS가 있으면 해당 브랜치, 없으면 `develop`(브랜치가 `hotfix/`면 `main`).
2. 현재 브랜치가 원격에 push되어 있는지, 로컬에 안 올라간 커밋이 없는지 확인하라:
   ```bash
   git rev-parse HEAD
   git rev-parse origin/{현재브랜치} 2>/dev/null
   ```
   - 원격 브랜치가 없거나 해시가 다르면 push되지 않은 커밋이 있는 것이다.
     사용자에게 알리고 중단하라 (commit-push 스킬로 먼저 올려야 한다).
3. 달 라벨을 정하라 — PR 제목의 타입과 같은 저장소 라벨이다. 라벨 이름에 이모지가 붙어 있으니 `gh label list`로 정확한 이름을 확인한다:

   | 제목 타입 | 라벨 |
   |---|---|
   | feat | `🌼 feat` |
   | fix | `🔨fix` |
   | refactor | `🧹refactor` |
   | test | `🧪 test` |
   | docs | `📚 docs` |
   | cicd | `🚦cicd` |
   | chore | `⚡️chore` |
   | hotfix | `🚑 hotfix` |

   - **`ready-to-merge`는 절대 달지 않는다** — develop → main 운영 반영을 일으키는 라벨이다(hook이 막는다).
4. 다음 명령으로 PR을 생성하라. 작성자 본인을 assignee로, 3의 라벨을 함께 단다:
   ```bash
   gh pr create --title "{제목}" --body-file {본문파일} --base {대상 브랜치} --assignee @me --label "{라벨}"
   ```
   - 라벨 이름이 틀려 실패하면 `gh label list`로 다시 확인해 고친다. PR이 이미 만들어진 뒤라면 `gh pr edit {번호} --add-assignee @me --add-label "{라벨}"`로 단다.
5. `gh pr view {번호} --json assignees,labels`로 assignee·라벨이 붙었는지 확인하고, PR URL·제목·base·assignee·라벨을 함께 사용자에게 보고하라.

> Skip 조건: 없음 (필수 Phase)
