-- 검토 표시와 쓰지 않는 컨셉 후보 컬럼을 지운다. 웹이 검토 배지·기능을 지웠고,
-- AI categorize 도 needs_review · proposed_concept_name · clip_concept_name 을 더는 쓰지 않는다(AI repo #144).
ALTER TABLE detail_folders DROP COLUMN IF EXISTS needs_review;
ALTER TABLE concept_assignments
    DROP COLUMN IF EXISTS needs_review,
    DROP COLUMN IF EXISTS proposed_concept_name,
    DROP COLUMN IF EXISTS clip_concept_name;
