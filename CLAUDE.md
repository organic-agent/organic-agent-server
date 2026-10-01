# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`wes`는 웨딩 사진 셀렉 서비스 백엔드다. 작가가 갤러리를 만들어 원본을 올리고, 초대받은
부부가 그 안에서 사진을 고른다. `organic-agent-server`의 `wes` 서비스/모듈이다.

## 기술 스택

- Kotlin 2.3.21 / Spring Boot 4.1.0 (servlet MVC — WebFlux 아님) / Gradle Kotlin DSL, JVM 21
- JPA + PostgreSQL + Flyway — 스키마는 Flyway 소유, `ddl-auto=validate`
- pgvector — 로컬·Testcontainers 모두 `pgvector/pgvector:pg16` (stock `postgres` 아님)
- Spring Security + OAuth2 (Google, Kakao, Naver) + JWT
- jackson-module-kotlin (Spring Boot 4.x의 `tools.jackson.module` 좌표)
- springdoc-openapi — 애노테이션은 `controller/docs`의 `*ControllerDocs` 인터페이스에
- 테스트: JUnit 5 + Testcontainers (**Docker 필요**)
- Kotlin 컴파일러 플래그: `-Xjsr305=strict`, `-Xannotation-default-target=param-property`

## 빌드 & 실행

시스템 Gradle이 아니라 wrapper(`./gradlew`)를 쓴다.

```bash
./gradlew build      # 빌드
./gradlew test       # 전체 테스트 (Docker 필요)
./gradlew test --tests "com.soma.wes.WesApplicationTests"               # 단일 클래스
./gradlew test --tests "com.soma.wes.WesApplicationTests.contextLoads"  # 단일 메서드
./gradlew bootRun    # 로컬 실행
```

## 임베딩과 인프라

- **클라이언트의 이미지 업로드·다운로드는 이 서버를 지나지 않는다.** 서버는 presigned S3 URL만
  발급하고 브라우저가 직접 올리고 받는다. 대용량 원본이 서버 메모리·대역폭을 먹지 않게 하려는
  원칙이지, 서버가 S3를 읽지 말라는 뜻이 아니다 — AI 호출(Bedrock 이미지 블록 등)을 위해
  미리보기(`previews/`) 몇 장을 읽는 것은 무방하다. 임베딩·프리뷰·EXIF·분석 점수처럼 모델이
  사진을 봐야 하는 일은 사진 50장 배치 단위로(`InvocationType.EVENT`) 호출되는 Lambda·GPU 워커의 일이다 —
  사진당 호출은 없다.
- 객체 키는 `galleries/{galleryId}/…`이고 미리보기는 `previews/` + 원본 키다. 환경은 키가 아니라
  버킷으로 갈린다 — prod는 운영 버킷, local 프로필은 인프라 `module.storage_dev`의 dev 버킷
  (`/wes/local/app.storage.bucket`). 키 조립은 `PhotoStorage.galleryPrefix`를 지난다.
- AI 실행기는 셋이다 — `embedder`(미리보기·DINOv3 벡터·EXIF, Lambda) → `score`(CLIP·미학·기술 점수·피사체, GPU 워커 또는
  Lambda 폴백) → `categorize`(백분위·연사·그룹 + Bedrock 이름·배정, Lambda). 코드는 sibling repo `../../organic-agent-ai`의
  최상위 디렉토리 하나 = 함수 하나(AI #35). **사진 한 장의 진행은 `photo_analysis` 행 하나가 말한다**(파이프라인 v2):
  `analysis` 도메인의 5초 파이프라인(`AnalysisPipelineService`: embed → score → categorize → folder 단계)이 업로드된 사진을 50장씩
  임베더에 배정하고(`EmbedStep`, `{galleryId, photoIds}`), 잡
  (`analysis_jobs`: ANALYZING → CATEGORIZING → DONE | FAILED)은 점수가 다 차기를 관측해 categorize를 한 번 부른 뒤
  AI 폴더를 자동 물질화하고 알림을 보낸다(`CategorizeStep`·`FolderStep`, `AiTaskSender`; 잡 전이는 조건부 UPDATE). 점수는 GPU EC2 워커가 `photo_analysis`를 직접
  집어 내고 wes는 켜고 끄기만 한다(`ScoreStep`·`ScoreWorkerPool`, `app.analysis.gpu.*`; 끄기는 워커의 유휴 30초 자기 정지가
  1차, wes는 2분 무진행 안전망). 워커가 없거나 멈추면 score Lambda 폴백을 `{galleryId, photoIds}`로 보낸다. Lambda는 잡 상태를
  쓰지 않는다(categorize 실패 시 `error` 한 컬럼 예외). 재분석 = `photo_analysis` 삭제(관리자 재처리). `folder`(폴더 세트 물질화)와
  `recommendation`(추천 + LLM)은 완성된 `photo_analysis`·배정 행만 읽는다. 설계는 `docs/plans/pipeline-v2-wes.md`
  (이전 설계 `docs/plans/analysis-domain.md`는 §12부터 대체됨), 컬럼 소유는 `.claude/rules/migration.md`.
- 인프라는 sibling repo `../../organic-agent-infra` (Terraform: VPC/ALB/EC2/RDS, 사진 S3 버킷
  + 로컬 개발용 dev 버킷, 임베딩 Lambda). `EMBEDDING_DIMENSION`은 이 repo 두 곳과 인프라 repo까지 세 곳이 일치해야
  한다 (`.claude/rules/migration.md`).
- 설정은 `src/main/resources/application.yml`이 `config/application-{cloud,db,variable}.yml`을
  import한다. 시크릿과 인프라 파생 값은 시작 시 AWS Parameter Store(`/wes/{local,prod}/`)에서
  온다.
- 운영 스크립트는 `scripts/`에 (추적됨):
  - `db-tunnel.sh [port]` — private RDS로 SSM 포트포워딩 (기본 15432). 자격증명은 실행 시
    Parameter Store에서 읽는다.
  - `reset-test-data.sh [local|remote] [--all] [--with-s3]` — 수동 테스트 데이터 초기화.
    계정은 기본 보존(토큰 유지). TRUNCATE 목록 규칙은 `.claude/rules/migration.md` 참조.
  - `delete-accounts.sh [local|remote] --email a@x.com [--email …] [--with-s3]` — 특정 OAuth 계정과
    그 계정에 딸린 워크스페이스·갤러리·사진·S3 객체만 삭제. 다른 사용자·관리자 계정은 남긴다.
  - `lambda/{embedder,score}.sh --gallery-id G --photo-ids 1,2,3` · `lambda/categorize.sh --gallery-id G --job-id J` — **로컬 Lambda
    대역.** 운영 Lambda 함수 하나 = 스크립트 하나(AI repo 최상위 모듈과 같은 이름), 인자는 Lambda 페이로드 키 그대로. 로컬 wes
    (local 프로필)의 `analysis` 스윕이 배정·categorize 때 이것을 띄운다(`LocalAiTaskSender`, 운영의 EVENT 자리).
    접속 정보는 `lib/ai-env.sh`.
  - `gpu/score-worker.sh` — **로컬 GPU 워커 대역.** 운영의 EC2 score 워커 한 대에 해당한다(AI repo `score worker --gpu --no-idle-stop`, 큐를 비우고 유휴 30초 뒤 종료).
    로컬 wes 에서 `app.analysis.gpu.enabled=true` 면 `ScoreStep` 이 "켜기" 자리에서 이것을 띄운다(`LocalScoreWorkerPool`).
  - `local-ai.sh <galleryId> [--skip-embed] [--skip-analyze]` — 위 셋을 잡 없이 갤러리 전체로 순서대로 도는 지름길(배정·폴더는
    저장되지 않음). 로컬 pg + dev 버킷(`/wes/local/app.storage.bucket`)을 쓴다.
  - `load/clone-gallery-photos.sh [local|remote] --source G --count N [--strip embed|score|categorize]` — 부하 실측용. 원본 갤러리의
    사진·분석 행을 새 갤러리 N개로 복제한다(같은 S3 객체를 가리켜 업로드 없음). `--strip`으로 그 단계부터 파이프라인이 다시 돌게 한다.
  - AI venv는 `scripts/lib/ai-venv.sh`가 `<모듈>/.venv`에 만든다(score venv에 categorize 포함).

## 규칙 참조

`.claude/rules/` — paths 매칭 파일 작업 시 자동 로드

- 프로젝트 구조 (패키지 배치) → `project-structure.md`
- Flyway 마이그레이션 / 스키마 변경 → `migration.md`
- 공통 컨벤션 (레이어 흐름, 예외, 객체 생성, 상수, 포맷팅, 네이밍, 주석) → `common.md`
- Kotlin 관용구 (널 처리, 불변성, 컬렉션, scope function, 타입 설계) → `kotlin.md`
- 계층별 컨벤션 → `controller.md`, `domain.md`, `dto.md`, `service.md`, `repository.md`,
  `infrastructure.md`, `support.md`
- 테스트 작성 (통합 테스트 인프라, 픽스처, 단언) → `test.md`

`.claude/spec/` — 스킬·작업에서 필요할 때만 참조 (자동 로드 아님)

- Git 작업 (커밋, 브랜치, PR) → `git-convention.md`
- 이슈·PR 본문 쓰는 법 (한 줄 요약, 왜/무엇을/확인, 전→후 표, 자가 점검) → `issue-pr-writing.md`
