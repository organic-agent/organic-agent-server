-- 분석 완료 알림은 잡마다 한 번만 발행한다. Lambda는 이 컬럼을 쓰지 않는다.
ALTER TABLE ai_analysis_jobs ADD COLUMN completion_notified_at timestamp(6) with time zone;

-- 도입 이전에 이미 끝난 잡을 새 완료로 알리지 않는다. 진행 중인 잡은 완료 뒤 스윕 대상이 된다.
UPDATE ai_analysis_jobs
SET completion_notified_at = COALESCE(finished_at, updated_at, created_at, now())
WHERE status = 'DONE';

CREATE INDEX idx_ai_analysis_jobs_completion_notification
    ON ai_analysis_jobs (id)
    WHERE status = 'DONE' AND completion_notified_at IS NULL;
