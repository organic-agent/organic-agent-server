-- 검토 표시를 지운다. 웹이 검토 배지·기능을 지웠고, AI categorize 도 needs_review 를 더는 쓰지 않는다(AI repo #144).
ALTER TABLE detail_folders DROP COLUMN IF EXISTS needs_review;
ALTER TABLE concept_assignments DROP COLUMN IF EXISTS needs_review;
