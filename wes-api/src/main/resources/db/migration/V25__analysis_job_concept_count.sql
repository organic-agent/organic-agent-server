-- 분석 잡에 사용자가 기억하는 컨셉 수(선택)를 남긴다. categorize 가 촬영 시각 구간을 이 수의 컨셉(1층)으로 묶는다.
-- NULL 이면 AI 가 개수를 정한다. 잡마다 남기는 이유: 다시 분석할 때 다른 수로 시험할 수 있어야 한다.
ALTER TABLE analysis_jobs ADD COLUMN concept_count integer;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_concept_count CHECK (concept_count IS NULL OR concept_count BETWEEN 1 AND 30);
