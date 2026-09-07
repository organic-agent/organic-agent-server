-- 분석 파이프라인 v2 — 사진 층(#164, docs/plans/pipeline-v2-wes.md Phase 1).
--
-- 사진 한 장의 분석 진행은 photo_analysis 행 하나가 말한다(embedding → clip_embedding → technical_pct, 실패는 error).
-- photos.status 는 "S3 에 원본이 있나"(PENDING·UPLOADED)만 답한다. 같은 사실을 두 곳에 두던 EMBEDDED 와
-- 임베더의 옛 품질 점수(technical_quality_*, ARNIQA sub_scores 가 대체)는 지운다.
-- 아직 운영 서비스가 아니라 값 변환 대신 삭제한다.

-- 1. photos.status 는 두 값. 임베딩 여부는 photo_analysis.embedding 이 말한다.
UPDATE photos SET status = 'UPLOADED' WHERE status = 'EMBEDDED';

-- 2. 임베더의 옛 품질 점수. CHECK(ck_photos_technical_quality_score)는 컬럼과 함께 사라진다.
ALTER TABLE photos
    DROP COLUMN technical_quality_score,
    DROP COLUMN technical_quality_signals,
    DROP COLUMN quality_analyzed_at;

-- 관리자 사진 교체 흐름의 QUALITY_ANALYSIS 잡도 같은 점수를 쓰던 것이라 함께 없어진다.
DELETE FROM admin_processing_jobs WHERE job_type = 'QUALITY_ANALYSIS';
ALTER TABLE admin_processing_jobs DROP CONSTRAINT ck_admin_processing_job_type;
ALTER TABLE admin_processing_jobs
    ADD CONSTRAINT ck_admin_processing_job_type CHECK (job_type IN ('DERIVATIVE', 'EMBEDDING', 'MOCK_RECALCULATION'));

-- 3. 임베더 배정 추적(wes 소유). 스윕이 UPLOADED·벡터 없음·dispatched_at NULL 인 사진을 50장씩 집어 보낸다.
--    임베더는 일시 실패한 장만 dispatched_at 을 NULL 로 되돌린다. embed_attempts 는 내부 재시도 카운터다.
ALTER TABLE photos
    ADD COLUMN dispatched_at  timestamp(6) with time zone,
    ADD COLUMN embed_attempts integer NOT NULL DEFAULT 0;

-- 4. 사진 단위 실패의 유일한 표시. 임베더(디코드 불가)·score(미리보기 없음)·wes(재시도 상한)가 쓰고,
--    배정·집기·기대 장수를 세는 쪽 전부가 error IS NULL 을 조건으로 건다.
ALTER TABLE photo_analysis ADD COLUMN error text;

-- 5. 인덱스 — 배정 SELECT, PENDING 보정·livePending, GPU 집기·scored 카운트.
CREATE INDEX idx_photos_embed_queue ON photos (gallery_id, id)
    WHERE status = 'UPLOADED' AND dispatched_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_photos_pending ON photos (created_at, updated_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_photo_analysis_unscored ON photo_analysis (photo_id)
    WHERE clip_embedding IS NULL AND error IS NULL;

-- 6. embedder role 계약 — V1 블록을 대체하는 전체 계약. status·technical_quality_* 를 더 쓰지 않고 dispatched_at 을 되돌리며,
--    photo_analysis.error 를 쓴다. photos 권한은 컬럼 단위라 누적되므로 REVOKE 뒤 다시 준다. 나머지 테이블은 V1 과 같다
--    (V1 은 이미 적용된 파일이라 고치지 않는다 — 계약 테스트는 이 블록만 실행한다).
-- EMBEDDER_GRANT_CONTRACT_BEGIN
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'embedder') THEN
        EXECUTE 'REVOKE ALL ON public.photos FROM embedder';
        EXECUTE 'GRANT SELECT (id, deleted_at) ON public.galleries TO embedder';
        EXECUTE 'GRANT SELECT (id, gallery_id, storage_key, status, deleted_at, preview_key, dispatched_at, '
                'taken_at, camera_make, camera_model, exposure_time, f_number, iso, width, height, '
                'byte_size, version) ON public.photos TO embedder';
        EXECUTE 'GRANT UPDATE (preview_key, dispatched_at, taken_at, camera_make, camera_model, '
                'exposure_time, f_number, iso, width, height, byte_size, version, updated_at) ON public.photos TO embedder';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON public.photo_analysis TO embedder';
        EXECUTE 'GRANT SELECT (id, attempt_count, job_type, target_type, target_id, revision_id, status, payload) '
                'ON public.admin_processing_jobs TO embedder';
        EXECUTE 'GRANT UPDATE (status, failure_code, last_run_at, updated_at) '
                'ON public.admin_processing_jobs TO embedder';
        EXECUTE 'GRANT SELECT (id, photo_id, storage_key) ON public.admin_photo_revisions TO embedder';
    END IF;
END
$$;
-- EMBEDDER_GRANT_CONTRACT_END
