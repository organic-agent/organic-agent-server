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

- `photo_analysis`(임베딩·분석 컬럼)·`concept_assignments`는 이 서버 밖(AI repo Lambda 셋
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
- `analysis_jobs`(V16, 파이프라인 v2)는 갤러리 한 번의 "폴더 만들기"만 맡는 한 층 상태 기계다 —
  `status` ANALYZING → CATEGORIZING → DONE | FAILED, 컬럼은 `id`·`gallery_id`·`status`·`dispatched_at`(categorize EVENT 시각)·
  `attempts`(categorize 호출 수)·`finished_at`·`error`·`version`·`created_at`·`updated_at`, 그리고 대기에 끝을 두는 V29의
  `progress_count`·`progress_at`(ANALYZING 진행 감시)·`categorizing_at`(잡 전체 기한)·`materialize_attempts`·`error_code`
  (`AnalysisFailureCode` — 응답에는 이 코드와 사용자 문장만 나가고 `error`는 내부 문장으로 DB에만 남는다)다. 이 서버가 상태 전부를 쓰고,
  Lambda(categorize, DB 유저 `photoselect`)는 실패했을 때 `error` 한 컬럼만 쓴다(GRANT `UPDATE (error, updated_at)`).
  단계·모드·force·result 컬럼은 없다 — 사진별 진행은 `photo_analysis` 행이 말하고, 임베더 배정은 잡과 무관하게 스윕이 한다
  (`EmbedStep`, `photos.dispatched_at`·`embed_attempts`). 재분석은 `photo_analysis` 행 삭제(`PhotoPipelineRepository.resetAnalysis`)다.
  `AnalysisJob`은 `@DynamicUpdate`다 — Lambda가 쓰는 `error`를 이 서버의 오래된 스냅샷이 덮지 않게 바뀐 컬럼만 UPDATE한다.
- 폴더 물질화 기록 테이블(`categorization_jobs`·`categorization_job_photos`)은 V17에서 지웠다 — "처리한 사진"은
  `detail_folder_assignments`가 말하고, 물질화 진입점은 `AiFolderMaterializer.materialize`(인가 없음, 멱등) 하나다.
  멱등의 표식은 V30의 `detail_folder_assignments.analysis_job_id`(이 사진을 처음 넣은 잡)다 — 기존 폴더에 합치기만 한 잡은 컨셉 폴더를
  만들지 않으므로 `concept_folders.analysis_job_id`로는 알아볼 수 없다. 사용자가 옮겨도 값은 남는다.
  관리자 리소스 `CATEGORIZATION_JOB`도 함께 지웠고 `AdminAuditTargetType`의 값만 옛 감사 로그 읽기용으로 남는다(V2의 ALBUM과 같은 방식).
- 잡의 지나간 일(전송·재전송·폴백·떼어내기·물질화·닫힘)은 V31의 `analysis_job_events`에 한 줄씩 남는다 — 로그의 `job.*` 줄과 같은 지점에서
  `AnalysisJobEventRecorder`가 쓴다. 판단에 쓰지 않는 운영 기록이고 기록 실패는 파이프라인을 멈추지 않는다 — 호출자의 트랜잭션에 싣지 않고 커밋 뒤에 새 트랜잭션으로 쓴다. `type`에는 CHECK가 없다.
- `photo_analysis`는 세 주체가 나눠 쓴다 — 임베더가 `embedding·embedding_model`, score 단계가
  `subjects·sub_scores·clip_embedding·pipeline_version`(1단계)·`quality_scored_at`(2단계 화질 점수, V40)·`quality_claimed_at`(2단계 찜 표시, V41 —
  계산 중 행 잠금을 쥐지 않으려는 것, 이 서버는 읽지 않음), categorize 단계가
  `embed_group_id·burst_id`(폴더용)와 `technical_pct·aesthetic_pct·burst_rank`(순위 — full 또는 rank 모드). `sub_scores`는 score·categorize가
  키를 나눠 쓰므로 통째로 덮지 말고 병합(`||`)한다. 진행 표시는 단계마다 하나다: 1단계 `clip_embedding` → 폴더 `embed_group_id` →
  2단계 `quality_scored_at` → 추천 `technical_pct`. 폴더는 `embed_group_id`까지만 기다리고, "추천 재료 완료"는 `PhotoAnalysis.isAnalyzed`
  (백분위까지 채워짐)로 판단한다(#274 2물결). DB 제약(V3 → V40): `ck_photo_analysis_scored`(pipeline_version ⇒ subjects·analyzed_at),
  `ck_photo_analysis_grouped`(그룹·연사 묶음은 함께), `ck_photo_analysis_ranked`(백분위 둘·순위는 함께, 있으면 그룹도). 배치가 쓰는 컬럼 묶음을
  바꾸면 이 제약들도 같이 본다.
- `ai_selection_jobs`·`ai_recommendations`는 2026-09-04부터 이 서버가 쓴다(추천 실행기 `AiSelectionJobRunner`).
  AI repo는 더 이상 이 테이블을 쓰지 않는다. 비교샷 테이블 `ai_pair_verdicts`는 기능과 함께 V22에서 지웠다 —
  photoselect GRANT 계약도 V22 블록이 V16 블록을 통째로 대체한다.
- 공용 선호 가중치 `preference_models`(V14)는 V33에서 지웠다. 쓰는 Lambda(`wes-preference`)가 배포된 적이 없고
  서버는 읽지 않았다(#240). 그 GRANT 블록(V14 `PREFERENCE_GRANT_CONTRACT`)도 테이블과 함께 의미를 잃었다.
- **GRANT는 마이그레이션 안에 둔다.** Lambda 전용 role은 Terraform 밖의 수동 생성이라, 새 테이블의 GRANT는
  `IF EXISTS (SELECT 1 FROM pg_roles …)` DO 블록을 `-- {NAME}_GRANT_CONTRACT_BEGIN/END` 마커로 감싸
  같은 마이그레이션에 넣는다(V15 embedder, V16 photoselect — V1 블록은 적용된 파일이라 고치지 않고 통째로 대체한다).
  운영(role 있음)은 배포 시 Flyway가 걸고
  로컬·테스트(role 없음)는 건너뛴다. `AdminEmbedderPrivilegeContractTest`가 그 블록을 꺼내 Lambda의 실제 SQL을
  role로 실행하므로 계약을 늘리면 거기에 SQL도 보탠다. identity 컬럼은 시퀀스 GRANT가 필요 없다.

## 용어집 이름 (V23)

- AI와 서버가 공유하는 테이블·컬럼 이름은 WES-DOCS `docs/glossary.md`가 정본이다. V23에서 옛 이름을 바꿨다 —
  `ai_concept_assignments`→`concept_assignments`(`parent_name`·`concept_name`→`concept_name`·`detail_name`),
  `ai_analysis_jobs`→`analysis_jobs`, `photo_category_assignments`→`detail_folder_assignments`, `photo_analysis.cluster_*`→`burst_*`,
  `model_version`→`pipeline_version`(`photo_analysis`·`preference_models`), `detail_folders.category`→`cut_type`,
  `ai_selection_jobs.folder_set_job_id`→`analysis_job_id`. 이 문서의 앞 절들이 V1~V22를 설명할 때도 테이블은 지금 이름으로 적는다.
- 이름을 바꾸는 마이그레이션은 AI repo 배포와 같은 창에서 적용한다(분석 멈춤 → wes 배포 → AI 배포 → 재개). 제약·인덱스 이름은
  카탈로그를 조회해 바꾼다(V23의 DO 블록) — 이미 지운 제약이 섞인 목록을 손으로 적지 않는다.

## 새 테이블의 부수 작업

- `scripts/reset-test-data.sh`의 `TRUNCATE` 목록에 추가한다. V9부터 FK가 있어서, 비우려는
  테이블을 참조하는 테이블이 하나라도 빠지면 Postgres가 문장 전체를 거절한다.
- 소프트 삭제 대상 테이블을 native SQL로 읽는다면 `deleted_at IS NULL`을 직접 건다 —
  `@SQLRestriction`은 JPA 경로만 지킨다 (rules/repository.md 참조).
