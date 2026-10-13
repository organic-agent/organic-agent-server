ALTER TABLE ai_selection_jobs
    ADD COLUMN request_prompt varchar(1000),
    ADD COLUMN requested_target_count integer,
    ADD COLUMN resolved_query jsonb,
    ADD CONSTRAINT ck_ai_selection_jobs_target_count CHECK (requested_target_count BETWEEN 1 AND 500);
