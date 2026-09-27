---
name: qa-domain
description: |
  리팩토링 전에 한 도메인의 현재 동작을 테스트로 고정한다. 진입점(API·스케줄러·리스너)을 전부 뽑아 기존 테스트와 대조하고, 빈 곳을 특성화 테스트로 채워 기준선을 만든다.
  Trigger: "category QA 해줘", "이 도메인 QA", "리팩토링 전에 동작 고정", "도메인 테스트 커버리지 점검", "/qa-domain <domain>"
  Do NOT use for: 클래스 하나의 테스트 작성(→ write-test), 리팩토링 진단·적용(→ refactor-domain), 실데이터 확인만(→ run-remote)
  Boundary: 프로덕션 코드를 수정하지 않는다. 테스트·픽스처와 보고서만 쓴다. 버그를 발견하면 현재 동작을 고정하고 보고만 한다.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash, AskUserQuestion
model: opus
effort: xhigh
---

# 도메인 QA — 동작 기준선 만들기

대상: $ARGUMENTS

**인자 형식**: `<domain>` (필수, `src/main/kotlin/com/soma/wes/` 아래 패키지 이름)
- 예: `category`, `recommendation`, `analysis`, `retouch`, `auth`, `security`

이 스킬의 결과물은 **"리팩토링 뒤에도 이 테스트가 초록이면 동작이 보존됐다"**고 말할 수 있는
테스트 묶음과 보고서다. 좋은 코드인지는 여기서 판단하지 않는다 — 그건 `refactor-domain`의 일이다.

보고서: `docs/improvements/refactor/{domain}.md` (docs/는 gitignore 대상인 개인 노트).
`refactor-domain`이 같은 파일의 뒷부분을 이어 쓴다.

## Phase 1: 진입점 인벤토리

도메인 안으로 들어오는 모든 문을 목록화한다. 컨트롤러만 보면 절반을 놓친다.

1. **REST**: `{domain}/controller/*Controller.kt`의 모든 핸들러 — HTTP 메서드, 경로, 부르는 서비스 메서드,
   인가 문(`requirePhotographer`/`requireCouple`/`requireViewer` 등).
2. **비동기·주기 진입점**: `@Scheduled`, `@EventListener`, `@TransactionalEventListener`, `@Async`,
   `ApplicationRunner` — `grep -rn "@Scheduled\|EventListener\|@Async\|ApplicationRunner" src/main/kotlin/com/soma/wes/{domain}`.
3. **다른 도메인이 부르는 문**: 이 도메인의 `support`·`service`를 주입받는 바깥 클래스 —
   `grep -rln "import com.soma.wes.{domain}\." src/main/kotlin | grep -v "/{domain}/"`.
4. **필터·설정** (`security`·`auth`): 필터 체인 순서, 허용 경로(`permitAll`), OAuth 콜백·JWT 발급·재발급 경로.
5. **외부 계약** (`analysis`·`recommendation`): Lambda·GPU 워커 페이로드, 워커가 직접 쓰는 컬럼
   (`.claude/rules/migration.md`의 컬럼 소유 표). 이것들은 wes 테스트로 다 고정할 수 없으니 목록에만 남긴다.

각 진입점마다 서비스 메서드의 **분기**(if/when/throw, 던지는 ErrorCode)를 적는다. 존재하지 않는
메서드·ErrorCode를 지어내지 마라 — 전부 소스에서 확인한 것만 쓴다.

> 다음 Phase 조건: 진입점 표(진입점 → 서비스 메서드 → 분기·ErrorCode)가 완성되었을 때

## Phase 2: 기존 테스트 매핑

1. `src/test/kotlin/com/soma/wes/{domain}/`와, 이 도메인을 부르는 다른 도메인 테스트를 읽는다.
2. Phase 1의 분기마다 커버 여부를 표시한다: ✅ 단언까지 있음 / ⚠️ 지나가지만 결과를 단언하지 않음 / ❌ 없음.
   예외 테스트가 `errorCode`까지 검증하지 않으면 ⚠️다 (타입만 보면 다른 코드로 회귀해도 통과한다).
3. 보고서의 `## QA` 절에 커버리지 표를 쓴다:

   ```markdown
   ## QA (YYYY-MM-DD)
   ### 커버리지
   | 진입점 | 서비스 메서드 | 분기 | 상태 | 테스트 |
   |---|---|---|---|---|
   | POST /api/v1/galleries/{id}/concept-folders/{conceptId}/detail-folders | FolderService.createDetail | 컨셉 없음 → CONCEPT_NOT_FOUND | ❌ | — |
   ```

> 다음 Phase 조건: 모든 분기에 상태가 매겨졌을 때

## Phase 3: 보강 범위 확인

❌·⚠️ 분기를 우선순위로 묶어 AskUserQuestion으로 채울 범위를 확인한다:

- **P1**: 리팩토링이 건드릴 가능성이 높은 곳 — 복잡한 메서드, 쓰기 경로, 인가·잠금·전부-아니면-거절
- **P2**: 단순 조회의 정상 경로
- **P3**: 외부 계약·운영 스크립트 경로 (wes 테스트로 고정 불가 → 수동 체크리스트로만)

사용자가 이미 범위를 말했다면 묻지 않고 진행한다.

> 다음 Phase 조건: 채울 분기 목록이 정해졌을 때

## Phase 4: 특성화 테스트 작성

`.claude/skills/write-test/SKILL.md`의 Phase 2~5(픽스처 → 작성 → 컴파일 → 실행)와
`.claude/rules/test.md`를 그대로 따른다. 이 스킬에서 다른 점만:

1. **현재 동작을 단언한다, 기대 동작이 아니라.** 코드가 이상해 보여도 지금 하는 일을 고정한다.
   이상하다고 판단한 동작은 테스트에 `// QA: ...` 주석을 달지 말고 보고서의 `### 발견한 문제`에 적는다
   (주석은 머지되면 소음이다 — `common.md`).
2. 명백한 버그(500, 데이터 유실, 인가 우회)라 현재 동작을 고정하는 것 자체가 해롭다면 그 테스트는
   쓰지 않고 보고서에만 적는다.
3. 한 서비스 클래스의 테스트는 기존 `{Service}Test`에 `@Nested`로 추가한다. 새 파일은 테스트 클래스가 없을 때만.
4. 프로덕션 코드는 고치지 않는다. 테스트하기 어려운 구조(`now()` 무인자, 정적 호출, 거대 메서드)는
   보고서의 `### 테스트 가능성 문제`에 적어 `refactor-domain`의 입력으로 넘긴다.

> 다음 Phase 조건: Phase 3의 목록이 전부 작성되고 컴파일이 통과했을 때

## Phase 5: 기준선 실행

1. 도메인 테스트: `./gradlew test --tests "com.soma.wes.{domain}.*"` (Docker 필요 — 꺼져 있으면 `open -a Docker` 후 대기).
2. 이 도메인을 부르는 다른 도메인 테스트도 돌린다 (Phase 1-3에서 찾은 도메인들).
3. 실패하면 **테스트 쪽을** 고친다. 원인이 프로덕션 버그면 해당 테스트를 빼고 보고서에 적는다.
4. 보고서에 기준선을 기록한다: 커밋 해시(`git rev-parse --short HEAD`), 실행한 명령, 테스트 수, 통과 수.
   `refactor-domain`은 이 기준선과 비교해 동작 보존을 판정한다.

> 다음 Phase 조건: 도메인·연관 테스트가 전부 초록일 때

## Phase 6: 실데이터 스모크 (선택)

로컬 테스트 데이터로는 드러나지 않는 동작(실제 임베딩·점수가 있는 갤러리의 추천·폴더 결과)이
있을 때만 사용자에게 제안하고, 동의하면 `.claude/skills/run-remote/SKILL.md`를 따른다.
**GET 요청만** 호출하고, 응답의 형태·건수를 보고서에 남긴다. 쓰기 API는 부르지 않는다.

> Skip 조건: 사용자가 원하지 않거나, 도메인에 실데이터 의존 동작이 없을 때 (`auth`·`security`는 대개 skip)

## Phase 7: 보고

보고서의 `## QA` 절을 마무리하고 사용자에게 요약한다:

- 커버리지 변화 (❌/⚠️ → ✅ 개수)
- 추가·수정한 테스트 파일 경로와 메서드 수
- `### 발견한 문제` — 버그 후보 (위치, 재현, 현재 동작, 기대 동작)
- `### 테스트 가능성 문제` — 리팩토링 입력
- `### 수동 체크리스트` — 외부 계약 등 테스트로 고정하지 못한 것
- 다음 단계: `/refactor-domain {domain}`

커밋은 하지 않는다. 사용자가 원하면 `commit-push` 스킬로 `test:` 커밋을 만든다.
