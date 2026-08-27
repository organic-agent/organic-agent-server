-- 사진 교체 후 QUALITY_ANALYSIS 결과. 점수는 운영자 판단 보조일 뿐 자동 폐기 조건이 아니다.
ALTER TABLE photos
    ADD COLUMN technical_quality_score DOUBLE PRECISION,
    ADD COLUMN technical_quality_signals JSONB,
    ADD COLUMN quality_analyzed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_photos_technical_quality_score
        CHECK (technical_quality_score IS NULL OR technical_quality_score BETWEEN 0 AND 100);

-- 운영 DB에 embedder 전용 role이 이미 있으면 exact-photo 작업에 필요한 테이블/컬럼만 연다.
-- 테스트·신규 설치처럼 role 생성 전인 환경에서는 migration을 막지 않는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'embedder') THEN
        EXECUTE 'GRANT SELECT (id, deleted_at) ON galleries TO embedder';
        -- Column-level UPDATE도 SET/WHERE 표현식이 읽는 기존 컬럼에는 SELECT 권한이 필요하다.
        -- db.py의 batch/exact-photo SQL이 실제 참조하는 열만 열어 둔다.
        EXECUTE 'GRANT SELECT (id, gallery_id, storage_key, status, deleted_at, embedding, preview_key, '
                'taken_at, camera_make, camera_model, exposure_time, f_number, iso, width, height, '
                'byte_size, version) ON photos TO embedder';
        EXECUTE 'GRANT UPDATE (embedding, status, preview_key, taken_at, camera_make, camera_model, '
                'exposure_time, f_number, iso, width, height, byte_size, technical_quality_score, '
                'technical_quality_signals, quality_analyzed_at, version, updated_at) ON photos TO embedder';
        EXECUTE 'GRANT SELECT (id, attempt_count, job_type, target_type, target_id, revision_id, status, payload) '
                'ON admin_processing_jobs TO embedder';
        EXECUTE 'GRANT UPDATE (status, failure_code, last_run_at, updated_at) '
                'ON admin_processing_jobs TO embedder';
        EXECUTE 'GRANT SELECT (id, photo_id, storage_key) ON admin_photo_revisions TO embedder';
    END IF;
END
$$;
