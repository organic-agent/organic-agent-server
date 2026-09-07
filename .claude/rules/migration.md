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
  `preference_models.w_emb`(V14)는 DINOv3 ⊕ CLIP이라 `vector(2n)`이다 — 같이 바꾼다.

## AI가 쓰는 테이블

- `photo_analysis`(임베딩·분석 컬럼)·`ai_concept_assignments`는 이 서버 밖(AI repo Lambda 셋
  embedder·score·categorize)이 직접 INSERT/UPDATE 한다. 엔티티의 그 컬럼은 읽기 전용 `val`이고,
  컬럼을 바꾸면 AI repo(`score/store.py`·`categorize/store.py`·`embedder/db.py`)도 함께 바꾼다.
  전용 DB 유저(`embedder`, `photoselect`)의 GRANT도 새 테이블마다 필요하다.
- **사진 한 장의 분석 진행은 `photo_analysis` 행 하나가 말한다**(V15, 파이프라인 v2). `photos.status`는 PENDING·UPLOADED 둘뿐이고
  "S3에 원본이 있나"만 답한다 — 임베딩·점수·백분위 여부는 `embedding`·`clip_embedding`·`technical_pct` 유무로, 결정적 실패는
  `photo_analysis.error`(임베더·score·이 서버가 공유하는 유일한 실패 표시)로 본다. 분석 행은 이 서버가 미리 만들지 않고 임베더가
  첫 배치에서 UPSERT로 만든다. 진행을 셀 때는 LEFT JOIN(`PhotoPipelineRepository.progressOf`).
- `photos.dispatched_at`·`embed_attempts`는 이 서버가 임베더 배정에 쓴다(V15). 임베더는 일시 실패한 장의 `dispatched_at`만
  NULL로 되돌리고 `status`는 건드리지 않는다. 옛 품질 점수(`technical_quality_*`)와 관리자 `QUALITY_ANALYSIS` 잡은 V15에서 지웠다.
  embedder GRANT 계약은 V15 블록이 V1 블록을 통째로 대체한다(V1은 적용된 파일이라 고치지 않는다).
- `ai_analysis_jobs`는 이 서버(`analysis` 도메인)와 Lambda가 나눠 쓴다(V4). 이 서버: `stage`·`stage_attempts`·
  `dispatched_at`·`observed_progress`·`force`와 잡을 닫는 `status` DONE/FAILED. Lambda: `stage_status`의 시작(claim)·끝,
  `heartbeat_at`, `result`의 단계별 키(`||` 병합), `error`. 단계 상태를 아직 쓰지 않는 Lambda(AI Phase 0 이전,
  `app.analysis.lambda-reports-stage=false`)는 `status`를 직접 RUNNING·DONE으로 옮기고 이 서버가 그 경우를 함께 다룬다.
  EMBED 단계는 임베더가 잡을 모른다 — 이 서버가 `photo_analysis`를 관측해 열고 닫는다.
- `photo_analysis`는 세 주체가 나눠 쓴다 — 임베더가 `embedding·embedding_model`, SCORE 잡이
  `subjects·sub_scores·clip_embedding·model_version`, CATEGORIZE 잡이 `technical_pct·aesthetic_pct·cluster_*·
  embed_group_id`. 두 잡 사이에 `model_version`만 있고 백분위가 없는 창이 있으므로 "분석 완료"는
  `PhotoAnalysis.isAnalyzed`(백분위까지 채워짐) 하나로만 판단한다. DB 제약도 잡 단위다(V3):
  `ck_photo_analysis_scored`(model_version ⇒ subjects·analyzed_at), `ck_photo_analysis_categorized`
  (백분위·연사·그룹은 전부 있거나 전부 없음). 배치가 쓰는 컬럼 묶음을 바꾸면 이 두 제약도 같이 본다.
- `ai_selection_jobs`·`ai_recommendations`·`ai_pair_verdicts`는 2026-09-04부터 이 서버가 쓴다
  (추천 실행기 `AiSelectionJobRunner`, 비교샷 `PairVerdictJudge`). AI repo는 더 이상 이 테이블을 쓰지 않는다.
- `preference_models`(V14)는 AI repo Lambda `preference`(`preference/store.py`, DB 유저 `photoselect`)가
  INSERT/UPDATE 한다 — 갤러리 마감마다 가중치 행을 새로 넣고, 게이트를 통과하면 이전 `active`를 내리고 새 행을
  `active=true`로 둔다(부분 유니크 인덱스로 활성 행은 하나). 이 서버는 읽기만 한다. 컬럼 순서 계약은
  `feature_spec`(`pref-v1`)이고, 컬럼을 바꾸면 AI repo `preference/store.py`·`features.py`도 함께 바꾼다.
- **GRANT는 마이그레이션 안에 둔다.** Lambda 전용 role은 Terraform 밖의 수동 생성이라, 새 테이블의 GRANT는
  `IF EXISTS (SELECT 1 FROM pg_roles …)` DO 블록을 `-- {NAME}_GRANT_CONTRACT_BEGIN/END` 마커로 감싸
  같은 마이그레이션에 넣는다(V1 embedder·photoselect, V14 preference). 운영(role 있음)은 배포 시 Flyway가 걸고
  로컬·테스트(role 없음)는 건너뛴다. `AdminEmbedderPrivilegeContractTest`가 그 블록을 꺼내 Lambda의 실제 SQL을
  role로 실행하므로 계약을 늘리면 거기에 SQL도 보탠다. identity 컬럼은 시퀀스 GRANT가 필요 없다.

## 새 테이블의 부수 작업

- `scripts/reset-test-data.sh`의 `TRUNCATE` 목록에 추가한다. V9부터 FK가 있어서, 비우려는
  테이블을 참조하는 테이블이 하나라도 빠지면 Postgres가 문장 전체를 거절한다.
- 소프트 삭제 대상 테이블을 native SQL로 읽는다면 `deleted_at IS NULL`을 직접 건다 —
  `@SQLRestriction`은 JPA 경로만 지킨다 (rules/repository.md 참조).
