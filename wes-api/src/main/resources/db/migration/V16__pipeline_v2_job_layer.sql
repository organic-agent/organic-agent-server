-- 분석 파이프라인 v2 — 잡 층(#166, docs/plans/pipeline-v2-wes.md Phase 2).
--
-- ai_analysis_jobs 는 갤러리 한 번의 "폴더 만들기"만 맡는다: ANALYZING(사진별 임베딩·점수를 관측) → CATEGORIZING(categorize
-- Lambda 한 번) → DONE | FAILED. 두 층 상태 기계(stage·stage_status·heartbeat·result·observed_progress)와 mode·force 는
-- 사진 층(V15, photo_analysis 행이 진행을 말한다)이 대신하므로 지운다. 아직 운영 서비스가 아니라 변환 대신 삭제한다 —
-- 살아 있던 옛 잡은 새 상태로 옮길 수 없어 지우고, 끝난 잡(DONE·FAILED)은 폴더 세트의 analysis_job_id 가 가리키므로 남긴다.

-- 1. 옛 상태 기계로 진행 중이던 잡. 배포 전에 없어야 하지만 있으면 새 잡을 다시 요청하면 된다.
DELETE FROM ai_analysis_jobs WHERE status IN ('PENDING', 'RUNNING');

-- 2. 상태 CHECK·부분 유니크는 새 값 집합으로 다시 만든다. 완료 알림 마커 인덱스는 컬럼과 함께 사라진다.
ALTER TABLE ai_analysis_jobs DROP CONSTRAINT ck_ai_analysis_jobs_status;
DROP INDEX uk_ai_analysis_jobs_active;
DROP INDEX idx_ai_analysis_jobs_completion_notification;

-- 3. 컬럼 10개 삭제(각 컬럼만 보는 CHECK — mode·stage·stage_status — 는 함께 사라진다), attempts 추가.
--    attempts 는 categorize EVENT 를 보낸 횟수(3회 상한), dispatched_at 은 마지막으로 보낸 시각(20분 타임아웃)이다.
ALTER TABLE ai_analysis_jobs
    DROP COLUMN mode,
    DROP COLUMN stage,
    DROP COLUMN stage_status,
    DROP COLUMN stage_attempts,
    DROP COLUMN heartbeat_at,
    DROP COLUMN observed_progress,
    DROP COLUMN force,
    DROP COLUMN result,
    DROP COLUMN started_at,
    DROP COLUMN completion_notified_at,
    ADD COLUMN attempts integer NOT NULL DEFAULT 0;

ALTER TABLE ai_analysis_jobs
    ADD CONSTRAINT ck_ai_analysis_jobs_status CHECK (status IN ('ANALYZING', 'CATEGORIZING', 'DONE', 'FAILED'));
CREATE UNIQUE INDEX uk_ai_analysis_jobs_active ON ai_analysis_jobs (gallery_id)
    WHERE status IN ('ANALYZING', 'CATEGORIZING');

-- 4. photoselect role 계약 — V1 블록을 대체하는 전체 계약. Lambda(score·categorize)는 잡 테이블의 상태를 더 쓰지 않는다.
--    categorize 가 실패했을 때 error 한 컬럼만 남기고, 잡을 닫는 것(DONE·FAILED)은 wes 다. 테이블 단위 UPDATE 를 거두고
--    컬럼 단위로 다시 준다. 나머지 테이블은 V1 과 같다(V1 은 이미 적용된 파일이라 고치지 않는다 — 계약 테스트는 이 블록만 실행한다).
-- PHOTOSELECT_GRANT_CONTRACT_BEGIN
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'photoselect') THEN
        EXECUTE 'REVOKE ALL ON public.ai_analysis_jobs FROM photoselect';
        EXECUTE 'GRANT SELECT ON public.galleries, public.photos, public.photo_analysis, '
                'public.concept_folders, public.detail_folders, public.photo_category_assignments, '
                'public.photo_selections, public.photo_selection_items, '
                'public.ai_analysis_jobs, public.ai_concept_assignments, public.ai_selection_jobs, '
                'public.ai_recommendations, public.ai_pair_verdicts '
                'TO photoselect';
        EXECUTE 'GRANT INSERT, UPDATE ON public.photo_analysis, public.ai_concept_assignments, '
                'public.ai_recommendations, public.ai_pair_verdicts '
                'TO photoselect';
        EXECUTE 'GRANT UPDATE (error, updated_at) ON public.ai_analysis_jobs TO photoselect';
        EXECUTE 'GRANT UPDATE ON public.ai_selection_jobs TO photoselect';
    END IF;
END
$$;
-- PHOTOSELECT_GRANT_CONTRACT_END
