---
name: refactor-domain
description: |
  한 도메인을 진단해 컨벤션 위반·비효율·복잡도·죽은 코드를 findings 표로 만들고, 사용자가 고른 항목만 동작을 보존하며 작은 단계로 적용한다.
  Trigger: "category 리팩토링 해줘", "이 도메인 정리하자", "도메인 코드 개선", "죽은 코드 찾아줘", "/refactor-domain <domain>"
  Do NOT use for: 동작 고정·테스트 보강(→ qa-domain, 먼저 실행), 변경분 리뷰(→ code-review), 새 기능 구현
  Boundary: 선택받은 항목만 고친다. API 제거·응답 형태 변경·스키마 변경처럼 동작이 바뀌는 항목은 따로 명시 승인을 받는다. 커밋·PR은 하지 않는다.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash, AskUserQuestion
model: opus
effort: xhigh
---

# 도메인 리팩토링 — 진단 → 선택 → 적용

대상: $ARGUMENTS

**인자 형식**: `<domain> [--diagnose-only]`
- 예: `category`, `retouch --diagnose-only`
- `--diagnose-only`: Phase 2까지만 (findings 표만 만들고 코드는 건드리지 않음)

보고서: `docs/improvements/refactor/{domain}.md`의 `## 리팩토링` 절 (`qa-domain`이 앞의 `## QA` 절을 쓴다).

## Phase 0: 전제 확인

1. **브랜치**: `main`이면 멈추고 `open-issue` 스킬로 `refactor/{이슈}-{domain}-...` 브랜치를 만들자고 제안한다.
   작업 브랜치라면 `gh pr list --head <branch> --state merged`로 이미 머지된 브랜치가 아닌지 확인하고
   (squash 머지라 끝난 브랜치도 살아 보인다), `git fetch && git rebase origin/develop`으로 develop 위에 올린다.
2. **기준선**: 보고서의 `## QA` 절에서 기준선(커밋·테스트 수)을 읽는다. 없으면
   `./gradlew test --tests "com.soma.wes.{domain}.*"`를 돌려 지금 결과를 기준선으로 기록하고,
   테스트가 얇은 도메인이면(진입점 대비 테스트가 절반 미만) **`/qa-domain {domain}`을 먼저 돌리자고 제안한다.**
   사용자가 그대로 진행하길 원하면 진행하되, Phase 3에서 테스트 없는 분기는 건드리지 않는다.

> 다음 Phase 조건: 작업 브랜치 위에 있고 기준선이 기록되었을 때

## Phase 1: 진단 (코드 수정 금지)

1. 규칙을 읽는다: `.claude/rules/kotlin.md`, `common.md`, 그리고 계층 파일 전부
   (`controller`·`service`·`support`·`domain`·`repository`·`dto`·`infrastructure`·`project-structure`).
2. `src/main/kotlin/com/soma/wes/{domain}/` 전체를 읽고 아래 분류로 findings를 모은다.
   각 finding은 **소스에서 확인한 위치(`파일:줄`)가 있어야 한다** — 추측으로 채우지 마라.

| 분류 | 찾는 것 |
|---|---|
| `dead` | 아래 "죽은 코드 판정"을 통과한 미사용 코드 |
| `perf` | 루프 안 단건 조회·조립(N+1), 루프 안 `find`/`filter`(O(n²)), `@Transactional` 안의 S3·Lambda·Bedrock·HTTP 호출, 필요 없는 전체 로드(`findAll` 후 필터), 잠금 범위 과다 |
| `tx` | 클래스 레벨 `@Transactional`, 같은 클래스 안 `@Transactional` 자기 호출, 읽기에 `readOnly` 누락 |
| `complexity` | 메서드 50줄 초과, 중첩 3단 초과, 클래스 400줄 초과, 한 클래스가 여러 유스케이스 축을 가짐, 문자열 키 분기 |
| `kotlin` | `kotlin.md` 위반: `!!`, `Optional`·`findById().orElseThrow()`, 가변 컬렉션 노출, `forEach`로 모으기·체인 끝이 아닌 `forEach`, scope function 중첩, `else` 있는 닫힌 `when`, 엔티티 `==` 비교·엔티티를 `Set`/`Map` 키로, `@Transactional` 안의 `runCatching`, 무인자 `now()` |
| `convention` | 계층 규칙 위반: 역방향 의존, 서비스 안 중첩 데이터 클래스, `~Dto` 접미사 누락, 포트 위치, 스코프 없는 조회, `require`로 비즈니스 실패, 매직넘버, named argument 누락 |
| `dup` | 서비스 여러 곳에 반쯤 복제된 검증·조회·조립 (support로 옮길 신호) |
| `test` | `qa-domain`이 넘긴 테스트 가능성 문제 |

3. **죽은 코드 판정** — 호출부 검색만으로 지우지 마라. 이 서버를 부르는 곳은 여러 저장소에 있다:
   - wes 내부: `grep -rn "<이름>" src/main/kotlin src/test/kotlin` — 테스트에서만 쓰이면 "테스트 전용"으로 표시.
   - **REST 엔드포인트**: 웹 클라이언트 `../../organic-agent-web/src/lib/api/*.ts`(와 `src/app/**/_lib`)에서 경로를 grep한다.
     관리자 API(`/api/v1/admin/**`)는 웹이 아닌 소비자가 있을 수 있으니 사용자에게 확인한다.
   - **분석 계약** (`analysis`·`recommendation`·`category`): AI repo `../../organic-agent-ai`가 읽고 쓰는 컬럼·페이로드 키,
     `.claude/rules/migration.md`의 컬럼 소유 표. wes에서 안 읽히는 컬럼·엔티티 필드도 워커가 쓰고 있을 수 있다.
   - **운영 스크립트**: `scripts/**`가 참조하는 테이블·경로·설정 키.
   - **설정**: `@ConfigurationProperties` 필드는 `application*.yml`과 Parameter Store 키(`/wes/{local,prod}/`) 양쪽을 본다.
   - **ErrorCode**: 던지는 곳이 없는 enum 상수. 단, 웹이 코드 문자열(`CATEGORY_404_1`)로 분기하는지 웹 repo에서 grep.
   - **이력**: `git log -S "<메서드명>" --oneline`으로 이전 정리 커밋이 그 코드를 일부러 남겼는지 본다.
     웹 호출이 0건이어도 다른 흐름(예: 개인 갤러리 CSV 내보내기가 읽는 초안 회차 — #189가 남긴 `addPhotos`)을
     떠받치는 경로일 수 있다. 커밋 본문이 남긴 이유를 적었으면 `의심`으로 올리고 그 이유를 findings에 인용한다.
   - 판정 결과를 `확실`(모든 소비자에서 0건) / `의심`(일부만 확인)으로 나눈다. `의심`은 제거 대상이 아니라 질문 대상이다.

4. 보고서에 findings 표를 쓴다:

   ```markdown
   ## 리팩토링 (YYYY-MM-DD, 기준 커밋 abc1234)
   ### Findings
   | # | 분류 | 위치 | 문제 | 제안 | 동작 변화 | 테스트 커버 |
   |---|---|---|---|---|---|---|
   | 1 | kotlin | recommendation/service/AiSelectionJobRunner.kt:93 | findById().orElseThrow() 반복 | findByIdOrNull ?: error(...) 로 통일 | 없음 | ✅ |
   | 2 | dead | retouch/controller/...:40 | 웹 호출 0건 | 엔드포인트·서비스 메서드 제거 | **API 제거** | — |
   ```

   `동작 변화`는 `없음` / `API 제거` / `응답 변경` / `스키마 변경` / `성능만` 중 하나.

> 다음 Phase 조건: 도메인 전체 파일을 읽었고 findings 표가 보고서에 쓰였을 때

## Phase 2: 선택

1. findings를 효과 대비 위험 순으로 정렬해 사용자에게 요약한다 (표 전체는 보고서에 있으니 채팅에는 번호·한 줄씩).
2. AskUserQuestion(multiSelect)으로 적용할 항목을 받는다. 항목이 많으면 분류 단위로 묶어 묻는다.
3. `동작 변화`가 `없음`·`성능만`이 아닌 항목은 선택되더라도 **한 번 더 명시 확인**한다 —
   API 제거는 웹 배포와 순서가 맞아야 하고, 스키마 변경은 Flyway 마이그레이션과 워커 호환이 걸린다.
4. `--diagnose-only`면 여기서 보고하고 끝낸다.

> 다음 Phase 조건: 적용할 항목 목록이 확정되었을 때

## Phase 3: 적용 — 항목 하나씩

선택된 항목을 **의존 순서대로 하나씩** 적용한다 (죽은 코드 제거 → 구조 이동 → 관용구 정리 순이 충돌이 적다).
항목마다:

1. 수정한다. 규칙:
   - 선택받은 항목의 범위만 고친다. 지나가다 본 다른 문제는 findings에 추가만 하고 고치지 않는다.
   - 동작을 바꾸지 않는다 (`동작 변화: 없음`인 항목). 테스트가 없는 분기를 바꿔야 한다면 멈추고,
     먼저 그 분기의 테스트를 (`write-test` 규칙대로) 추가한 뒤 진행한다.
   - 다른 도메인은 호출부 수정만 한다 (시그니처가 바뀐 경우).
   - 클래스·패키지를 옮기면 테스트 패키지도 미러링해 옮긴다.
   - 엔티티 필드·테이블을 없애면 `.claude/rules/migration.md`를 따르고 `scripts/reset-test-data.sh` TRUNCATE 목록을 맞춘다.
   - 엔드포인트를 없애면 `controller/docs`의 `*ControllerDocs`, 요청·응답 DTO, 전용 ErrorCode까지 함께 정리한다.
2. `./gradlew compileKotlin compileTestKotlin`
3. `./gradlew test --tests "com.soma.wes.{domain}.*"` — 기준선보다 실패가 늘면 그 항목을 되돌리고 원인을 보고한다.
   테스트 수가 줄었다면 삭제된 기능의 테스트인지 확인한다 (그 외의 감소는 실패로 본다).
4. 보고서 findings 표의 해당 행에 `적용됨`을 표시한다.

> 다음 Phase 조건: 선택된 항목이 모두 적용되었거나 되돌려졌을 때

## Phase 4: 전체 검증

1. `./gradlew build` — 전체 컴파일과 전체 테스트. 다른 도메인에서 깨지면 고치거나(호출부) 해당 항목을 되돌린다.
2. `grep -rn "!!" src/main/kotlin/com/soma/wes/{domain}` 처럼 적용한 `kotlin` 항목이 다시 남아 있지 않은지 확인한다.
3. 변경이 크면 사용자에게 `/code-review`와 `/simplify` 실행을 제안한다 (이 스킬이 대신 돌리지 않는다).

> 다음 Phase 조건: `./gradlew build`가 통과했을 때

## Phase 5: 보고

보고서의 `## 리팩토링` 절을 마무리하고 사용자에게 요약한다:

- 적용 / 되돌림 / 보류 항목 수와 목록
- 변경 파일 수, 삭제된 줄 수 (`git diff --stat origin/develop`)
- API 제거처럼 **웹·AI·인프라 쪽 후속 작업**이 필요한 항목
- 새로 발견해 findings에 추가만 한 항목
- `kotlin.md`나 계층 규칙에 없어서 판단이 갈렸던 패턴 — 규칙에 추가할지 사용자에게 묻는다

커밋하지 않는다. 사용자가 원하면 `commit-push` 스킬이 변경을 성격별(`refactor:` / `chore:` 제거 / `test:`)로 묶는다.
