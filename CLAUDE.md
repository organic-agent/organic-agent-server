# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`wes`는 웨딩 사진 셀렉 서비스 백엔드다. 작가가 갤러리를 만들어 원본을 올리고, 초대받은
부부가 그 안에서 사진을 고른다. `organic-agent-server`의 `wes` 서비스/모듈이다.

소스는 Gradle 모듈별 `src/`에 둔다. 패키지는 기존 `com.soma.wes`를 유지한다.

- `wes-domain`: 공통 엔티티·정책·서비스·인프라. 실행 앱이 아닌 라이브러리다.
- `wes-api`: 공개 컨트롤러·보안 설정·`WesApplication`, Flyway 마이그레이션의 소유자다.
- `wes-admin-api`: 관리자 기능·인증 필터·`WesAdminApiApplication`.
- 공용 테스트 지원 코드·픽스처·설정은 `wes-domain/src/testFixtures`에서 두 API에 제공한다.

## 기술 스택

- Kotlin 2.3.21 / Spring Boot 4.1.0 (servlet MVC — WebFlux 아님) / Gradle Kotlin DSL, JVM 21
- JPA + PostgreSQL + Flyway — 스키마는 Flyway 소유, `ddl-auto=validate`
- pgvector — 로컬·Testcontainers 모두 `pgvector/pgvector:pg16` (stock `postgres` 아님)
- Spring Security + OAuth2 (Google, Kakao, Naver) + JWT
- jackson-module-kotlin (Spring Boot 4.x의 `tools.jackson.module` 좌표)
- springdoc-openapi — 애노테이션은 `controller/docs`의 `*ControllerDocs` 인터페이스에
- 테스트: JUnit 5 + Testcontainers (**Docker 필요**)
- Kotlin 컴파일러 플래그: `-Xjsr305=strict`, `-Xannotation-default-target=param-property`, `-Xwarning-level=REDUNDANT_ELSE_IN_WHEN:error`

## 빌드 & 실행

시스템 Gradle이 아니라 wrapper(`./gradlew`)를 쓴다.

```bash
./gradlew build      # 빌드
./gradlew test       # 전체 테스트 (Docker 필요)
./gradlew :wes-api:test --tests "com.soma.wes.WesApplicationTests"               # 단일 클래스
./gradlew :wes-api:test --tests "com.soma.wes.WesApplicationTests.contextLoads"  # 단일 메서드
./gradlew :wes-api:bootRun        # 공개 API 로컬 실행
./gradlew :wes-admin-api:bootRun  # 관리자 API 로컬 실행
```

## 브랜치와 배포

- 작업 브랜치는 `origin/develop`에서 분기해 develop으로 PR(squash). `develop` push = dev 배포(`cd-dev.yml`),
  `main` push = **운영 배포**(`cd-prod.yml`). 운영 반영은 develop → main PR에 `ready-to-merge` 라벨을 붙여
  `ff-merge.yml`이 fast-forward 한다. 자세한 것은 `.claude/spec/git-convention.md`.
- 그래서 main push · `ready-to-merge` 라벨 · `cd-prod` 실행은 Claude가 하지 않는다(아래 "규칙 강제 장치"의 Bash hook이 막는다).

## 이미지와 스토리지

- **클라이언트의 이미지 업로드·다운로드는 이 서버를 지나지 않는다.** <!-- CT-10 --> 서버는 presigned S3 URL만
  발급하고 브라우저가 직접 올리고 받는다. 대용량 원본이 서버 메모리·대역폭을 먹지 않게 하려는
  원칙이지, 서버가 S3를 읽지 말라는 뜻이 아니다 — AI 호출(Bedrock 이미지 블록 등)을 위해
  미리보기(`previews/`) 몇 장을 읽는 것은 무방하다. 임베딩·프리뷰·EXIF·분석 점수처럼 모델이
  사진을 봐야 하는 일은 사진 50장 배치 단위로(`InvocationType.EVENT`) 호출되는 Lambda·GPU 워커의 일이다 <!-- INF-12 --> —
  사진당 호출은 없다.
- 객체 키는 `galleries/{galleryId}/…`이고 미리보기는 `previews/` + 원본 키다. 환경은 키가 아니라
  버킷으로 갈린다 — prod는 운영 버킷, dev 서버는 `wes-dev-photos-*`(`/wes/dev/`), local 프로필은 인프라
  `module.storage_local`의 로컬 버킷 `wes-local-photos-*`(`/wes/local/app.storage.bucket`). 키 조립은 `PhotoStorage.galleryPrefix`를 지난다. <!-- INF-11 -->

## AI 분석 파이프라인

- AI 실행기는 셋이다 — `embedder`(미리보기·DINOv3 벡터·EXIF, Lambda) → `score`(CLIP·미학·기술 점수·피사체, GPU 워커 또는
  Lambda 폴백) → `categorize`(백분위·연사·그룹 + Bedrock 이름·배정, Lambda). 코드는 sibling repo `../../organic-agent-ai`의
  최상위 디렉토리 하나 = 함수 하나(AI #35).
- **사진 한 장의 진행은 `photo_analysis` 행 하나가 말한다**(파이프라인 v2).
- `analysis` 도메인의 5초 파이프라인(`AnalysisPipelineService`: embed → score → categorize → folder 단계)이 업로드된 사진을 50장씩
  임베더에 배정한다(`EmbedStep`, `{galleryId, photoIds}`).
- 잡(`analysis_jobs`: ANALYZING → CATEGORIZING → DONE | FAILED)은 점수가 다 차기를 관측해 categorize를 한 번 부른 뒤
  AI 폴더를 자동 물질화하고 알림을 보낸다(`CategorizeStep`·`FolderStep`, `AiTaskSender`; 잡 전이는 조건부 UPDATE).
- 점수는 GPU EC2 워커가 `photo_analysis`를 직접 집어 내고 wes는 켜고 끄기만 한다(`ScoreStep`·`ScoreWorkerPool`, `app.analysis.gpu.*`;
  끄기는 워커의 유휴 30초 자기 정지가 1차, wes는 2분 무진행 안전망). 워커가 없거나 멈추면 score Lambda 폴백을 `{galleryId, photoIds}`로 보낸다.
- Lambda는 잡 상태를 쓰지 않는다(categorize 실패 시 `error` 한 컬럼 예외). 재분석 = `photo_analysis` 삭제(관리자 재처리).
- `folder`(폴더 세트 물질화)와 `recommendation`(추천 + LLM)은 완성된 `photo_analysis`·배정 행만 읽는다.
- 설계는 `docs/plans/2026-09-07/pipeline-v2-wes.md`(이전 설계 `docs/plans/2026-09-04/analysis-domain.md`는 §12부터 대체됨),
  컬럼 소유는 `.claude/rules/migration.md`.

## 인프라와 설정

- 인프라는 sibling repo `../../organic-agent-infra` (Terraform: VPC/ALB/EC2/RDS, 사진 S3 버킷
  + 로컬 개발용 로컬 버킷, 임베딩 Lambda; dev 서버는 `environments/dev` 스택). `EMBEDDING_DIMENSION`은 이 repo 두 곳과 인프라 repo까지 세 곳이 일치해야
  한다 (`.claude/rules/migration.md`).
- 각 API의 `src/main/resources/application.yml`이 `wes-domain`의 `config/application-{cloud,db,variable}.yml`을 import한다.
  공개 API는 `config/application-api-runtime.yml`(DB 커넥션 풀 분리·관리 포트 등 공개 앱 실행 설정)도 import한다 — 관리자 앱은 쓰지 않는다.
  시크릿과 인프라 파생 값은 시작 시 AWS Parameter Store에서 온다(공개 API `/wes/{local,dev,prod}/`, 관리자 API `/wes/admin-api/{local,dev,prod}/`).

## 스크립트 (`scripts/`, 추적됨)

폴더 = 실행 주체 — `deploy/`(CD가 서버·러너에서) · `ci/`(CI 검사) · `local/`(로컬 앱의 AI 대역) · `dev-tools/`(사람이 손으로) ·
`load/`(측정 회차) · `lib/`(공용 라이브러리, `env.sh`). 규격(환경 인자·머리 주석·언어)은 `.claude/rules/scripts.md`.

- **환경 인자는 `local | dev | prod`이고 필수다**(기본값 없음). 옛 이름 `remote`는 "prod로 바뀌었다" 오류로 멈춘다.
  `prod` 데이터 쓰기와 운영 DB 쓰기는 Claude가 하지 않는다 — 사용자가 직접 실행한다(Bash hook이 `prod`·`remote`를 막는다).
  환경별 이름(SSM·터널 포트·리소스 접두사·API 주소)은 `scripts/lib/env.sh`에만 있다.
- `dev-tools/db-tunnel.sh dev|prod [port]` — private RDS로 SSM 포트포워딩 (prod 15432, dev 15433). 자격증명은 실행 시
  Parameter Store에서 읽는다. 운영 DB 조회는 `PGOPTIONS='-c default_transaction_read_only=on'`을 붙인 읽기 전용 세션으로만.
- `dev-tools/reset-test-data.sh local|dev|prod [--all] [--with-s3]` — 수동 테스트 데이터 초기화.
  계정은 기본 보존(토큰 유지). TRUNCATE 목록 규칙은 `.claude/rules/migration.md` 참조.
- `dev-tools/delete-accounts.sh local|dev|prod --email a@x.com [--email …] [--with-s3]` — 특정 OAuth 계정과
  그 계정에 딸린 워크스페이스·갤러리·사진·S3 객체만 삭제. 다른 사용자·관리자 계정은 남긴다.
- `local/lambda/{embedder,score}.sh --gallery-id G --photo-ids 1,2,3` · `local/lambda/categorize.sh --gallery-id G --job-id J` — **로컬 Lambda
  대역.** 운영 Lambda 함수 하나 = 스크립트 하나(AI repo 최상위 모듈과 같은 이름), 인자는 Lambda 페이로드 키 그대로. 로컬 wes
  (local 프로필)의 `analysis` 스윕이 배정·categorize 때 이것을 띄운다(`LocalAiTaskSender`, 운영의 EVENT 자리).
  접속 정보는 `local/lib/ai-env.sh`.
- `local/gpu/score-worker.sh` — **로컬 GPU 워커 대역.** 운영의 EC2 score 워커 한 대에 해당한다(AI repo `score worker --gpu --no-idle-stop`, 큐를 비우고 유휴 30초 뒤 종료).
  로컬 wes 에서 `app.analysis.gpu.enabled=true` 면 `ScoreStep` 이 "켜기" 자리에서 이것을 띄운다(`LocalScoreWorkerPool`).
- `local/local-ai.sh <galleryId> [--skip-embed] [--skip-analyze]` — 위 셋을 잡 없이 갤러리 전체로 순서대로 도는 지름길(배정·폴더는
  저장되지 않음). 로컬 pg + 로컬 버킷(`/wes/local/app.storage.bucket`)을 쓴다.
- `load/clone-gallery-photos.sh local|dev|prod --source G --count N [--strip embed|score|categorize]` — 부하 실측용. 원본 갤러리의
  사진·분석 행을 새 갤러리 N개로 복제한다(같은 S3 객체를 가리켜 업로드 없음). `--strip`으로 그 단계부터 파이프라인이 다시 돌게 한다.
- AI venv는 `scripts/local/lib/ai-venv.sh`가 `<모듈>/.venv`에 만든다(score venv에 categorize 포함).

## 규칙 강제 장치

규칙(`.claude/rules`)은 세 겹으로 지킨다. 검사 명령의 출처는 하나 — `.claude/skills/rule-audit/reference/checks.md`.

- **편집·명령 직전(PreToolUse hook, `.claude/hooks/guard_*.py`)** — 되돌릴 수 없는 것만 막는다.
  - develop에 이미 들어간(= dev·운영 DB에 적용된) `V*__*.sql` 마이그레이션의 Edit·Write와 Bash 수정·삭제(`sed -i`·`rm`·`>>` 등) → 새 V 파일로.
    이 브랜치에서 새로 만든 V 파일은 커밋한 뒤에도 고칠 수 있다.
  - 운영 데이터 쓰기(`prod`·옛 `remote` 데이터 스크립트, 읽기 전용 옵션 없는 15432 `psql`)와 운영 배포(main push, `ready-to-merge` 라벨,
    `gh workflow run cd-prod`, base가 main인 `gh pr merge`, 배포 스크립트 직접 실행) → 사용자에게 `! <명령>`으로 직접 실행을 부탁한다.
- **답을 끝낼 때(Stop hook, `.claude/hooks/stop_check.py`)** — `origin/develop`과 갈라진 지점 대비 바꾼 파일에서 규칙 위반 수가 늘면
  끝내지 못하고 고친다. 규칙이 허용하는 경우라고 판단되면 까닭을 말하고 넘어간다(같은 내용으로는 다시 막지 않는다).
- **PR(CI `rules` job)** — 저장소 전체를 기준 브랜치와 비교해 새 위반이 있으면 실패. Claude Code 없이 `python3`·`git`·`rg`만으로 돈다.
  로컬 확인: `python3 .claude/skills/rule-audit/scripts/run_checks.py --compare origin/develop [--full]`.
- 예외는 `.claude/skills/rule-audit/reference/allowlist-*.txt`에 `경로` 한 줄 + 바로 위 `# 까닭` 주석(리뷰에서 보인다).
- 기존 위반까지 포함한 전체 점검과 읽고 판단하는 규칙은 `/rule-audit`(보고서만 쓴다).

## 규칙 참조

`.claude/rules/` — paths 매칭 파일 작업 시 자동 로드

- 프로젝트 구조 (패키지 배치) → `project-structure.md`
- Flyway 마이그레이션 / 스키마 변경 → `migration.md`
- 공통 컨벤션 (레이어 흐름, 예외, 객체 생성, 상수, 포맷팅, 네이밍, 주석) → `common.md`
- Kotlin 관용구 (널 처리, 불변성, 컬렉션, scope function, 타입 설계) → `kotlin.md`
- 계층별 컨벤션 → `controller.md`, `entrypoint.md`(scheduler·listener 입구), `domain.md`, `dto.md`, `service.md`, `repository.md`,
  `infrastructure.md`, `support.md`
- 테스트 작성 (통합 테스트 인프라, 픽스처, 단언) → `test.md`
- 스크립트 폴더(실행 주체)·환경 인자(`local|dev|prod`, 필수)·머리 주석·언어 → `scripts.md`
- 부하 측정 스크립트·회차 진행 (회차는 사용자가 실행, 출력은 `scripts/load/lib/report.sh`) → `load-test.md`

`.claude/spec/` — 스킬·작업에서 필요할 때만 참조 (자동 로드 아님)

- Git 작업 (커밋, 브랜치, PR) → `git-convention.md`
- 이슈·PR 본문 쓰는 법 (한 줄 요약, 왜/무엇을/확인/판단, 전→후 표, 자가 점검) → `issue-pr-writing.md`

작업 설계 하네스 — `/design-plan <요구사항>`: 요구사항을 유형(B1~B7·S1·S2)으로 라우팅해 이슈 → 단계별 결정·문서화 → 구현 → PR까지.
규칙과 강의 용어(아키텍처 드라이버·택틱·패턴·트레이드오프·ADR, W²HO)의 뜻은 `.claude/skills/design-plan/reference/`(`glossary.md`·`types.md`·`stages.md`).
