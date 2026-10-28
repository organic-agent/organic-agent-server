# WES Server

Wedding Easy Select(웨딩 사진 셀렉 서비스)의 백엔드다. 사진작가가 갤러리를 만들어 원본을 올리고, 초대받은 부부가 그 안에서 사진을 고른다. 서버는 갤러리·사진·셀렉·보정 요청 같은 도메인 API를 내주고, 업로드된 사진의 AI 분석 파이프라인(임베딩 → 점수 → 자동 폴더)을 조율한다.

| 저장소 | 역할 |
|---|---|
| **organic-agent-server** (이 저장소) | 공개 API · 관리자 API · DB 스키마(Flyway) · AI 파이프라인 조율 |
| [organic-agent-web](https://github.com/organic-agent/organic-agent-web) | 작가·부부·게스트 웹 |
| [organic-agent-backoffice](https://github.com/organic-agent/organic-agent-backoffice) | 최고 관리자 내부 운영 화면 (관리자 API를 호출) |
| [organic-agent-ai](https://github.com/organic-agent/organic-agent-ai) | AI 실행기 — embedder · score · categorize (Lambda · GPU 워커) |
| [organic-agent-infra](https://github.com/organic-agent/organic-agent-infra) | Terraform — VPC · ALB · EC2 · RDS · S3 · Lambda |
| [WES-DOCS](https://organic-agent.github.io/WES-DOCS/) | 제품·아키텍처·데이터 모델·스프린트 문서 사이트 |

---

## 모듈 구조

Gradle 멀티 모듈이다. 소스는 모듈 안 `src/`에만 두고, 패키지는 `com.soma.wes`를 쓴다.

| 모듈 | 무엇 | 실행 |
|---|---|---|
| `wes-domain` | 공통 엔티티·정책·서비스·인프라 어댑터. 실행 앱이 아닌 라이브러리. 테스트 지원 코드·픽스처는 `src/testFixtures`에서 두 API에 제공 | — |
| `wes-api` | 공개 API. 컨트롤러·보안 설정·`WesApplication`. **Flyway 마이그레이션의 유일한 소유자** | `:8080` (관리 포트 `:8090`) |
| `wes-admin-api` | 관리자 API. 백오피스 전용 기능·인증 필터·`WesAdminApiApplication`. 스키마를 바꾸지 않는다 | `:8081` |

도메인은 `wes-domain/src/main/kotlin/com/soma/wes/` 아래 한 패키지씩이다.

`studio`(작가·스튜디오) · `workspace`(개인/스튜디오 작업공간) · `gallery`(갤러리·초대·공유 링크) · `photo`(사진·업로드) · `analysis`(AI 분석 파이프라인) · `folder`(폴더·AI 폴더 세트) · `recommendation`(AI 폴더 추천) · `selection`(사진 선택·전달) · `collab`(별점·좋아요·댓글) · `retouch`(보정 요청·PDF) · `billing`(요금제·쿠폰) · `trash`(휴지통) · `notification` · `activity` · `auth` / `user` / `security` · `global`(공통 설정·예외)

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| Language · Runtime | Kotlin 2.3 · JVM 21 |
| Framework | Spring Boot 4.1 (servlet MVC) · Spring Security · OAuth2 Client (Google · Kakao · Naver) · JWT |
| Persistence | JPA(Hibernate) · PostgreSQL 16 + pgvector · Flyway (`ddl-auto=validate`) |
| AWS | S3 presigned URL · Lambda · EC2 · Bedrock · SQS(분석 실패 큐) · Parameter Store (spring-cloud-aws) |
| API 문서 | springdoc-openapi — `/swagger-ui.html` (공개 API만) |
| 관측 | Spring Actuator · Grafana Alloy 사이드카 → Loki · 구조화 로그 |
| 테스트 | JUnit 5 · Testcontainers (`pgvector/pgvector:pg16`) — **Docker 필요** |
| 빌드 · 배포 | Gradle Kotlin DSL · Docker (`eclipse-temurin:21-jre`, arm64) · GitHub Actions · SSM Run Command |

---

## 아키텍처 요약

- **요청 경로**: 웹 → ALB(HTTPS) → `wes-api :8080` → RDS PostgreSQL. 헬스체크는 별도 관리 포트 `:8090`의 actuator로 받아 사용자 요청이 스레드를 다 쥐어도 liveness가 줄 서지 않는다.
- **사진 바이트는 서버를 지나지 않는다.** 서버는 presigned S3 URL만 발급하고 브라우저가 S3에 직접 올리고 받는다. 서버가 S3를 읽는 건 AI 호출용 미리보기 몇 장뿐이다.
- **AI 분석 파이프라인(v2)**: 업로드된 사진을 50장씩 묶어 `embedder`(미리보기·DINOv3 벡터·EXIF) → `score`(CLIP·미학·기술 점수, GPU 워커 또는 Lambda 폴백) → `categorize`(그룹·Bedrock 이름 짓기) 순으로 돌린다. 단계 전환과 잡 상태는 이 서버가 소유하고, 실행기는 `organic-agent-ai`의 Lambda·GPU 워커다. 사진 한 장의 진행은 `photo_analysis` 행 하나가 말한다. 끝나면 AI 폴더를 물질화하고 알림을 보낸다.
- **관리자 경로**: 백오피스 → Tailscale 전용 호스트 → `wes-admin-api :8081`. 공개 인터넷에 열지 않는다.
- **설정**: 비밀이 아니고 인프라에 종속되지 않는 값은 저장소(`wes-domain/src/main/resources/config/`)에, 시크릿·인프라 파생 값은 시작 시 AWS Parameter Store에서 읽는다(공개 API `/wes/{local,dev,prod}/`, 관리자 API `/wes/admin-api/{local,dev,prod}/`).
- **주기 작업 리더**: 앱 서버가 2대 이상이어도 스케줄러는 Postgres 행 하나의 임대(lease)로 한 대만 돈다.

인프라 구성도와 상세 아키텍처는 [infra 저장소](https://github.com/organic-agent/organic-agent-infra)와 [WES-DOCS](https://organic-agent.github.io/WES-DOCS/)에 있다.

---

## 실행 방법

### 요구사항

- JDK 21, Docker (로컬 DB와 Testcontainers)
- AWS 자격증명 — `local` 프로필도 시작 시 Parameter Store `/wes/local/`을 읽는다 (DB 접속 정보·OAuth 클라이언트·JWT 시크릿·버킷 등)

### 1. 로컬 DB

pgvector 확장이 들어 있는 이미지를 쓴다. 순정 `postgres`로는 마이그레이션의 `CREATE EXTENSION vector`가 실패해 앱이 뜨지 않는다.

```bash
docker compose -f docker-compose.local.yml up -d
```

### 2. 앱 실행

```bash
./gradlew :wes-api:bootRun --args='--spring.profiles.active=local'        # 공개 API  → http://localhost:8080/swagger-ui.html
./gradlew :wes-admin-api:bootRun --args='--spring.profiles.active=local'  # 관리자 API → http://localhost:8081
```

공개 API가 시작하면서 Flyway로 스키마를 만든다(V1 → 최신). 관리자 API는 스키마를 건드리지 않으므로 공개 API를 먼저 띄운다.

### 3. 로컬 AI 분석 (선택)

로컬에는 Lambda가 없다. `local` 프로필의 "AI 분석"은 `scripts/lambda/{embedder,score,categorize}.sh`를 운영의 Lambda 자리에서 띄우고, GPU 워커 자리는 `scripts/gpu/score-worker.sh`가 맡는다. sibling 저장소 `../organic-agent-ai`와 그 venv가 필요하다(`scripts/lib/ai-venv.sh`가 만든다).

---

## 명령어

| 명령어 | 설명 |
|---|---|
| `./gradlew build` | 전체 빌드 (테스트 포함) |
| `./gradlew test` | 전체 테스트 — Docker 필요. `wes-api`·`wes-admin-api` 테스트가 나란히 돌며 각자 Postgres 컨테이너를 띄운다 |
| `./gradlew :wes-api:test --tests "com.soma.wes.WesApplicationTests"` | 단일 테스트 클래스 |
| `./gradlew :wes-api:bootRun` | 공개 API 로컬 실행 |
| `./gradlew :wes-admin-api:bootRun` | 관리자 API 로컬 실행 |
| `./gradlew :wes-api:bootJar` | 배포용 JAR (`wes-api/build/libs/`) |

시스템 Gradle이 아니라 wrapper(`./gradlew`)를 쓴다.

---

## 브랜치와 배포

흐름은 `feature → develop → main`이다. 작업 브랜치는 `{type}/{이슈번호}-{slug}`(예: `fix/420-login-redirect`), 커밋은 `{type}: 내용(#이슈번호)`. 타입은 `feat` · `fix` · `refactor` · `hotfix` · `docs` · `test` · `cicd` · `chore`. 자세한 규칙은 `.claude/spec/git-convention.md`.

| 브랜치 | 머지되면 | 워크플로 |
|---|---|---|
| PR → `develop` / `main` | 테스트 · Actions run 길이 검사 · 관리자 배포 경계 검사 | `ci.yml` |
| `develop` | dev 서버 배포 (`dev.api.easyselect.kr`) | `cd-dev.yml` |
| `main` | 운영 배포 (`api.easyselect.kr`) | `cd-prod.yml` |

- **운영 반영**: develop → main PR에 `ready-to-merge` 라벨을 붙이면 `ff-merge.yml`이 CI·dev 배포 통과를 확인하고 develop 커밋을 그대로 main에 올린다(fast-forward). 머지 버튼은 쓰지 않는다.
- **hotfix**: `main`에서 분기해 main으로 PR. 머지되면 `sync-develop.yml`이 develop을 main 위로 맞춘다.
- **배포 방식**: JAR 빌드 → arm64 이미지를 GHCR에 푸시 → 운영은 ECR로 복사(ASG의 새 서버가 인스턴스 역할로 받는다) → GitHub OIDC로 배포 역할 assume → `scripts/rolling-deploy.sh`가 **앱 서버 한 대씩** ALB 대상에서 빼고 SSM Run Command(`deploy-public-ssm.sh`)로 컨테이너를 교체한 뒤 healthy를 확인하고 다시 넣는다. 한 대라도 실패하면 그 서버를 직전 릴리스로 되돌리고 멈춘다. 관리자 API는 `deploy-admin-api-ssm.sh`로 전용 호스트에 배포한다. 장기 AWS 키·SSH 키는 쓰지 않는다.

---

## 프로젝트 구조

```text
.
├─ wes-domain/                 # 공통 엔티티·정책·서비스·인프라 (라이브러리) + testFixtures
│  └─ src/main/resources/config/   # application-{cloud,db,variable,api-runtime}.yml
├─ wes-api/                    # 공개 API · WesApplication · db/migration (Flyway)
├─ wes-admin-api/              # 관리자 API · WesAdminApiApplication
├─ scripts/                    # 운영·개발 스크립트 (아래)
├─ monitoring/                 # Grafana Alloy 설정 (로그 → Loki)
├─ deploy/                     # 관리자 호스트 systemd 유닛
├─ docker-compose.local.yml    # 로컬 pgvector Postgres
├─ docker-compose.{dev,prod}.yml · docker-compose.admin-api.{dev,prod}.yml   # 배포 슬롯
├─ Dockerfile.api · Dockerfile.admin-api
├─ .github/workflows/          # ci · cd-dev · cd-prod · ff-merge · sync-develop
├─ .claude/                    # 에이전트 규칙(rules) · 스킬(skills) · 명세(spec)
├─ CLAUDE.md · AGENTS.md       # 저장소 작업 지침 (사람도 읽는 기본 명세)
└─ docs/                       # 개인 노트 — gitignore 대상. 팀 공유 문서는 WES-DOCS
```

### 운영 스크립트 (`scripts/`)

| 스크립트 | 용도 |
|---|---|
| `db-tunnel.sh [prod\|dev] [port]` | private RDS로 SSM 포트포워딩 |
| `reset-test-data.sh`, `delete-accounts.sh` | 테스트 데이터 초기화 · 특정 OAuth 계정과 딸린 데이터 삭제 |
| `lambda/{embedder,score,categorize}.sh`, `gpu/score-worker.sh`, `local-ai.sh` | 로컬 AI 실행기 대역 (Lambda · GPU 워커 한 대) |
| `load/*.sh` | 부하 실측 — 갤러리 복제 · 타임라인 · 비용 · 사전 점검 |
| `rolling-deploy.sh`, `deploy-public-ssm.sh`, `deploy-admin-api-ssm.sh` | CD가 쓰는 배포 스크립트 |

---

## 협업 규칙

- 이슈·PR은 `.github/ISSUE_TEMPLATE/`·`.github/pull_request_template.md` 형식을 따른다. 본문 쓰는 법은 `.claude/spec/issue-pr-writing.md`.
- 코드 컨벤션은 `.claude/rules/`에 계층별로 있다(공통 · Kotlin · controller · service · domain · dto · repository · infrastructure · test · migration). 에이전트가 자동으로 읽지만 사람도 같은 규칙을 따른다.
- 스키마는 Flyway가 소유한다. 변경은 `wes-api/src/main/resources/db/migration/V{n}__{slug}.sql`로만 하고, 규칙은 `.claude/rules/migration.md`.
- `EMBEDDING_DIMENSION`은 이 저장소와 AI·infra 저장소가 일치해야 한다.

## 문서

- 제품·아키텍처·데이터 모델·용어집·스프린트: [WES-DOCS](https://organic-agent.github.io/WES-DOCS/) (서버 클릭형 아키텍처 문서는 `docs/architecture-description/`)
- 인프라 구성도·런북: [organic-agent-infra](https://github.com/organic-agent/organic-agent-infra)
- AI 실행기 구성·계약: [organic-agent-ai](https://github.com/organic-agent/organic-agent-ai)
- 이 저장소의 `docs/`는 개인 계획·실험 노트라 추적하지 않는다.
