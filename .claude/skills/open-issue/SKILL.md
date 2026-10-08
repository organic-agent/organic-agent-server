---
name: open-issue
description: |
  작업을 대화로 구체화한 뒤 종류에 맞는 GitHub 이슈를 열고, 연결된 작업 브랜치를 생성·체크아웃한다.
  Trigger: "이슈 열어줘", "이거 작업 시작하자", "새 작업 시작하자", "기능/버그/리팩토링 이슈 만들어줘"
  Do NOT use for: 작업 유형 고르기·단계별 계획·구현(→ design-plan), 이미 열린 이슈에 브랜치만 파는 경우(직접 git)
  Boundary: 이슈 생성과 브랜치 생성·체크아웃까지만 수행한다. 작업 계획과 구현은 이 스킬 범위 밖이다.
allowed-tools: Read, Grep, Glob, Bash
model: sonnet
effort: xhigh
---

# 작업 발의 (이슈 + 브랜치)

본 스킬은 '무엇을'과 '왜'를 담은 Github 이슈를 열며, 해당 이슈에 대한 작업 브랜치를 생성한다.
'어떻게(구현)'은 본 스킬에서 담당하지 않는다.

작업은 기능 구현(feat), 수정(fix), 리펙토링(refactor), 긴급 수정(hotfix), 문서화(docs),
테스트 관련(test), CI/CD(cicd), 기타(chore)로 분류되며 작업 종류를 먼저 판별한 뒤, 그에 맞는
템플릿, 라벨, 브랜치 접두사를 사용한다.

## Phase 0: design-plan에서 넘어왔는지 확인

인자에 `작업 유형:` 줄과 종류·제목·설명(Why)·작업 항목·연관 도메인이 모두 있으면 `design-plan` 스킬이 이미 구체화·라우팅을 끝낸 것이다.
그때는 **Phase 1을 건너뛰고** Phase 2로 간다. 본문 맨 아래에 `작업 유형: …` 줄을 그대로 둔다(유형별로 이슈를 모으는 검색 키).

인자가 그렇지 않은 평소 호출이면 Phase 1부터 한다. 작업 유형을 아직 안 골랐고 일이 커 보이면(새 도메인·스키마·성능·리팩터링 구조) `/design-plan`을 먼저 제안한다.

## Phase 1: 작업 구체화 (대화)

**$ARGUMENTS가 애매하다고 판단되면, 사용자와의 대화로 구체화한 뒤 이슈를 생성하라**

1. $ARGUMENTS는 기능에 대한 명세이다. 비어있다면 사용자에게 어떤 작업을 할 지 물어보고 작업을 구체화하라.
2. $ARGUMENTS가 모호하다면, 사용자와의 인터렉션을 통해 아래 3가지 요소를 구체화하라
   - 정확히 무엇을(동작, 범위, 경계)
   - 왜(배경, 문제 상황)
   - 제약사항(있는 경우에)
3. 아래 다섯 요소가 확정되면 다음 Phase로 이동하라
   - 종류: feat / fix / refactor / hotfix / docs / test / cicd / chore 중 하나
   - 제목: 작업을 한 줄로 표현하는 명사형
   - 설명: 이 작업이 왜 필요한지 1~3문장
   - 작업 항목: 체크리스트로 쪼갠 하위 작업 목록
   - 연관 도메인

> 다음 Phase 조건: 종류·제목·설명·작업 항목·연관 도메인이 사용자와 함께 확정되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 2: 이슈 본문 작성

1. 종류별 title 접두사·이슈 라벨·브랜치 접두사는 `.claude/spec/git-convention.md`의 커밋 타입 표를 참조하라 (접두사 `{종류}:`, 브랜치 `{종류}/`, 라벨은 표의 이슈 라벨).
   이슈 본문 템플릿은 종류에 관계없이 공통 템플릿 `.github/ISSUE_TEMPLATE/issue-template.md`를 Read해서 본문 구조를 그대로 따른다.
2. `.claude/spec/issue-pr-writing.md`를 Read로 읽어라. **본문은 이 가이드대로 쓴다** — 템플릿은 섹션 이름을,
   가이드는 섹션 안을 채우는 방법(한 줄 요약, 한 줄에 한 사실, 약어 풀이)을 정한다.
3. Phase 1 결과를 template 규격에 맞춰 본문으로 구성하라:
   - 맨 위: `>` 한 줄 요약 — "무엇을 → 왜"를 한 문장에
   - 1. Issue Description: **왜**만 2~3줄 — 지금 무엇이 불편하거나 틀렸는지. 해결 방법은 쓰지 않는다
   - 2. Issue Task: 작업 항목을 `- [ ] {작업명}` 체크리스트 3~7개로. 하나하나가 "끝났다"고 말할 수 있는 크기로
   - 3. Related Domain: 해당 도메인만 `- [x]`, 나머지는 `- [ ]` 유지
4. 제목은 결과를 말하는 한국어 한 구절로 쓴다 (좋음: `사진 배정 이름을 세부 폴더 배정으로 변경`,
   나쁨: `PhotoFolderAssignment → DetailFolderAssignment (D9)`).
5. 가이드 끝의 **자가 점검** 항목을 확인해 걸리는 것을 고치고, 제목·본문을 사용자에게 보여 준 뒤 생성한다.
6. 본문을 스크래치 파일에 저장해두면 `gh` 전달이 안전하다 (`--body-file`로 넘김).

> 다음 Phase 조건: template 규격과 작성 가이드에 맞는 본문·라벨이 준비되고, 사용자가 초안을 확인했을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 3: 이슈 생성

1. 아래 명령으로 이슈를 생성하라 (title 접두사·라벨은 `.claude/spec/git-convention.md`의 커밋 타입 표를 참조하라):
   ```bash
   gh issue create --title "{접두}: {제목}" --body-file {본문파일} --label "{라벨}"
   ```
2. 출력된 이슈 URL에서 이슈 번호를 파싱하라 (다음 Phase에서 브랜치명에 사용).

> 다음 Phase 조건: 이슈가 생성되고 번호를 확보했을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 4: 브랜치 생성·체크아웃

1. 브랜치 컨벤션(`.claude/spec/git-convention.md`)을 따른다:
   `{종류}/{이슈번호}-{slug}` - 종류는 Phase 1에서 정한 타입, slug은 영문 kebab-case 간단 설명.
   - 예: fix 이슈 #420 "로그인 리다이렉트 오류" → `fix/420-login-redirect`
2. 작업 브랜치는 `develop`에서 분기한다 (컨벤션: `feature → develop → main`, `.claude/spec/git-convention.md` 참조).
   최신 develop을 받아 브랜치를 만들어라:
   ```bash
   git fetch origin develop
   git checkout -b {종류}/{이슈번호}-{slug} origin/develop
   ```
   - 단, hotfix는 `origin/main`에서 분기한다(머지 뒤 `sync-develop.yml`이 develop에 반영).
3. 브랜치를 원격에 반영하고 upstream을 정정하라:
   ```bash
   git push -u origin HEAD
   ```
   - `git checkout -b ... origin/develop`은 upstream을 `origin/develop`으로 잡아, 이후 `git push`가 브랜치명 불일치로 실패하고 브랜치가 원격에 없다. `-u origin HEAD`로 동일명 원격 브랜치를 만들고 upstream을 그쪽으로 재설정해 재발을 막는다.

> 다음 Phase 조건: 새 브랜치로 체크아웃되고 원격에 push(-u)되었을 때

> Skip 조건: 없음 (필수 Phase)
