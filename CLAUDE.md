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
- `embedder/`가 그 Lambda다 (Python, DINOv2, ECR 컨테이너 이미지). 같은 repo지만 Gradle이
  빌드하지 않는 별도 배포 경로이고, 자체 README를 따른다.
- 인프라는 sibling repo `../../organic-agent-infra` (Terraform: VPC/ALB/EC2/RDS, 사진 S3 버킷,
  임베딩 Lambda). `EMBEDDING_DIMENSION`은 이 repo 두 곳과 인프라 repo까지 세 곳이 일치해야
  한다 (`.claude/rules/migration.md`).
- 설정은 `src/main/resources/application.yml`이 `config/application-{cloud,db,variable}.yml`을
  import한다. 시크릿과 인프라 파생 값은 시작 시 AWS Parameter Store(`/wes/{local,prod}/`)에서
  온다.
- 운영 스크립트는 `scripts/`에 (추적됨):
  - `db-tunnel.sh [port]` — private RDS로 SSM 포트포워딩 (기본 15432). 자격증명은 실행 시
    Parameter Store에서 읽는다.
  - `reset-test-data.sh [local|remote] [--all] [--with-s3]` — 수동 테스트 데이터 초기화.
    계정은 기본 보존(토큰 유지). TRUNCATE 목록 규칙은 `.claude/rules/migration.md` 참조.

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
