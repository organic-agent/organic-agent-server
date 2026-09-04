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

- **이미지 바이트는 이 서버를 지나지 않는다.** 서버는 presigned S3 URL만 발급하고 브라우저가
  직접 업로드한다. 임베딩·프리뷰·EXIF는 갤러리당 한 번(`InvocationType.EVENT`) 호출되는
  Lambda의 일이다 — 사진당 호출은 없다.
- 객체 키는 `galleries/{galleryId}/…`이고 미리보기는 `previews/` + 원본 키다. 환경은 키가 아니라
  버킷으로 갈린다 — prod는 운영 버킷, local 프로필은 인프라 `module.storage_dev`의 dev 버킷
  (`/wes/local/app.storage.bucket`). 키 조립은 `PhotoStorage.galleryPrefix`를 지난다.
- Lambda는 셋이고 순서가 고정된 하나의 흐름이다 — `embedder`(미리보기·DINOv3 벡터·EXIF) → `score`(CLIP·미학·기술
  점수·피사체) → `categorize`(백분위·연사·그룹 + Bedrock 이름·배정). 코드는 sibling repo `../../organic-agent-ai`의
  최상위 디렉토리 하나 = 함수 하나(AI #35). 이 repo는 트리거·순서·상태·재호출을 `analysis` 도메인이 소유하고
  (`ai_analysis_jobs` 상태 기계, `StageInvoker`, 30초 스윕), 스키마를 소유한다. `category`(폴더 세트 실체화)와
  `recommendation`(추천·비교샷 + LLM)은 완성된 `photo_analysis`·배정 행만 읽는다. 설계·호환 규칙은
  `docs/plans/analysis-domain.md`, 컬럼 소유는 `.claude/rules/migration.md`.
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
  - `lambda/{embedder,score,categorize}.sh --gallery-id G [--job-id J] [--force]` — **로컬 Lambda 대역.** 운영 Lambda 함수
    하나 = 스크립트 하나(AI repo 최상위 모듈과 같은 이름), 인자는 Lambda 페이로드 키 그대로. 로컬 wes(local 프로필)의
    `analysis` 도메인이 "AI 분석" 버튼에서 단계마다 이것을 띄운다(`LocalProcessStageInvoker`, 운영의
    EVENT 자리). score는 `--job-id`가 있으면 AI repo 계약대로 categorize.sh를 이어 부른다. 접속 정보는 `lib/ai-env.sh`.
  - `local-ai.sh <galleryId> [--force] [--only-embed]` — 위 셋을 잡 없이 순서대로 도는 지름길(배정은 저장되지 않음).
    로컬 pg + dev 버킷(`/wes/local/app.storage.bucket`)을 쓴다.
  - AI venv는 `scripts/lib/ai-venv.sh`가 `<모듈>/.venv`에 만든다(score venv에 categorize 포함).

## 규칙 참조

`.claude/rules/` — paths 매칭 파일 작업 시 자동 로드

- 프로젝트 구조 (패키지 배치) → `project-structure.md`
- Flyway 마이그레이션 / 스키마 변경 → `migration.md`
- 공통 컨벤션 (레이어 흐름, 예외, 객체 생성, 상수, 포맷팅, 네이밍, 주석) → `common.md`
- 계층별 컨벤션 → `controller.md`, `domain.md`, `dto.md`, `service.md`, `repository.md`,
  `infrastructure.md`, `support.md`
- 테스트 작성 (통합 테스트 인프라, 픽스처, 단언) → `test.md`

`.claude/spec/` — 스킬·작업에서 필요할 때만 참조 (자동 로드 아님)

- Git 작업 (커밋, 브랜치, PR) → `git-convention.md`
