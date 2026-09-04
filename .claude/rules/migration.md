---
description: Flyway 마이그레이션과 스키마 변경 규칙
paths:
  - "src/main/resources/db/migration/*.sql"
  - "src/main/kotlin/**/domain/*.kt"
---

# 마이그레이션

## Flyway가 스키마를 소유한다

- `src/main/resources/db/migration`이 스키마의 유일한 출처다. `ddl-auto`는 모든 프로파일에서
  `validate` — 스타일 취향이 아니다. `photo_analysis.embedding`은 pgvector `vector(768)` 컬럼이고,
  `CREATE EXTENSION vector`가 어떤 테이블보다 먼저 실행돼야 하는데 `ddl-auto`는 그걸 못 한다.
- **엔티티·컬럼을 추가하면 마이그레이션을 함께 쓴다.** 예외 없다.
- prod는 V1으로 baseline되어 있다(Flyway보다 오래된 DB). V1은 빈 DB에서만 실행된다.

## 버전 번호

- main 병합 시점 기준 최신 +1. **rebase 후에는 번호 충돌을 직접 확인하라** — 파일명이 다르면
  git은 충돌로 잡지 못하고, Flyway가 기동에서 실패한다 (V17 trash / V17→V18 folder 전례).

## Postgres는 pgvector다

- 로컬(`docker-compose.local.yml`)과 Testcontainers 모두 `pgvector/pgvector:pg16`을 쓴다.
  stock `postgres` 이미지가 아니다.

## 세 곳이 일치해야 하는 값

- `EMBEDDING_DIMENSION`: `PhotoAnalysis.EMBEDDING_DIMENSION` ↔ 마이그레이션의 `vector(n)` ↔
  인프라 repo의 `embedding_dimension`(Lambda의 `EMBED_DIM`). 하나를 바꾸면 셋 다 바꾼다.

## AI가 쓰는 테이블

- `photo_analysis`(임베딩·분석 컬럼)·`ai_analysis_jobs`·`ai_concept_assignments`는 이 서버 밖
  (임베더 Lambda, AI 분석·naming 배치)이 직접 INSERT/UPDATE 한다. 엔티티의 그 컬럼은
  읽기 전용 `val`이고, 컬럼을 바꾸면 AI repo(`photoselect/store.py`·`embedder/db.py`)도
  함께 바꾼다. 전용 DB 유저(`embedder`, `photoselect`)의 GRANT도 새 테이블마다 필요하다.
- `ai_selection_jobs`·`ai_recommendations`·`ai_pair_verdicts`는 2026-09-04부터 이 서버가 쓴다
  (추천 실행기 `AiSelectionJobRunner`, 비교샷 `PairVerdictJudge`). AI repo는 더 이상 이 테이블을 쓰지 않는다.

## 새 테이블의 부수 작업

- `scripts/reset-test-data.sh`의 `TRUNCATE` 목록에 추가한다. V9부터 FK가 있어서, 비우려는
  테이블을 참조하는 테이블이 하나라도 빠지면 Postgres가 문장 전체를 거절한다.
- 소프트 삭제 대상 테이블을 native SQL로 읽는다면 `deleted_at IS NULL`을 직접 건다 —
  `@SQLRestriction`은 JPA 경로만 지킨다 (rules/repository.md 참조).
